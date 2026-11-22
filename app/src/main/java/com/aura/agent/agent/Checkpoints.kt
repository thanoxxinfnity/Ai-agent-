package com.aura.agent.agent

import com.aura.agent.data.CheckpointEntity
import com.aura.agent.data.Ids
import com.aura.agent.net.GitHub
import com.aura.agent.net.CloudClient
import kotlinx.serialization.json.*

/**
 * Automatic snapshots taken before the agent changes anything, grouped by turn, and the restore side.
 * GitHub: the branch head sha is saved. Cloud: a git tree of the whole working directory is saved under refs/aura.
 */
class Checkpoints(private val app: com.aura.agent.App) {
    private val dao get() = app.db.dao()

    suspend fun recordGithub(ctx: ToolCtx, repo: String, branch: String, headSha: String, label: String) {
        if (!ctx.state.snapshotted.add("gh:$repo@$branch")) return
        dao.upsertCheckpoint(CheckpointEntity(Ids.id(), ctx.chatId, ctx.turnId, Ids.now(), "github", "$repo@$branch", headSha, label))
    }

    /** Snapshots the git repo that contains [dir] inside the codespace. Returns false when it is not a git repo. */
    suspend fun recordCloud(ctx: ToolCtx, client: CloudClient, dir: String, label: String): Boolean {
        val top = runCatching {
            client.exec("git -C ${Sh.q(dir)} rev-parse --show-toplevel", 20).stdout.trim()
        }.getOrDefault("")
        if (top.isBlank() || !top.startsWith("/")) return false
        if (!ctx.state.snapshotted.add("cs:$top")) return true
        val id = Ids.id().take(8)
        val r = client.exec(SNAPSHOT, 120, top, mapOf("AURA_CP_ID" to id))
        val sha = r.stdout.lineSequence().firstOrNull { it.startsWith("CP ") }?.removePrefix("CP ")?.trim().orEmpty()
        if (sha.isBlank()) { ctx.state.snapshotted.remove("cs:$top"); return false }
        dao.upsertCheckpoint(CheckpointEntity(Ids.id(), ctx.chatId, ctx.turnId, Ids.now(), "cloud", top, sha, label))
        return true
    }

    /**
     * Restores every checkpoint of a turn (earliest state wins per target). GitHub branches are moved back after a backup
     * branch is created, so nothing is lost. Cloud snapshots start the user's codespace, which is stopped again afterwards.
     */
    suspend fun restoreTurn(turnId: String, cloud: CloudSession?): String {
        val cps = dao.checkpointsForTurn(turnId).filter { !it.restored }
        if (cps.isEmpty()) return "Nothing to restore."
        val out = StringBuilder()
        val token = app.store.githubToken.value
        val earliest = cps.groupBy { it.kind + ":" + it.target }.mapValues { it.value.minBy { c -> c.createdAt } }.values
        for (cp in earliest) {
            try {
                when (cp.kind) {
                    "github" -> {
                        if (token.isBlank()) { out.appendLine("Skipped ${cp.target}: GitHub is not signed in."); continue }
                        val (repo, branch) = cp.target.split("@", limit = 2)
                        val gh = GitHub(token, app.cloudApi)
                        val cur = gh.call("GET", "/repos/$repo/git/ref/heads/$branch").jsonObject["object"]!!.jsonObject["sha"]!!.jsonPrimitive.content
                        if (cur != cp.ref) {
                            gh.call("POST", "/repos/$repo/git/refs", buildJsonObject {
                                put("ref", "refs/heads/aura-backup/${System.currentTimeMillis() / 1000}-$branch".replace(' ', '-')); put("sha", cur)
                            })
                            gh.call("PATCH", "/repos/$repo/git/refs/heads/$branch", buildJsonObject { put("sha", cp.ref); put("force", true) })
                        }
                        out.appendLine("Restored $repo@$branch to ${cp.ref.take(7)} (previous state kept in a backup branch).")
                    }
                    "cloud" -> {
                        cloud ?: error("cloud session missing")
                        val c = cloud.client()
                        val r = c.exec(RESTORE, 180, cp.target, mapOf("AURA_SHA" to cp.ref))
                        out.appendLine(if (r.stdout.contains("RESTORED")) "Restored ${cp.target} in the codespace." else "Could not restore ${cp.target}: ${r.render().take(200)}")
                    }
                }
            } catch (e: Exception) {
                out.appendLine("Failed to restore ${cp.target}: ${e.message}")
            }
        }
        dao.markRestored(turnId)
        return out.toString().trim()
    }

    companion object {
        val SNAPSHOT = """
export GIT_INDEX_FILE="${'$'}(mktemp -u /tmp/aura-idx.XXXXXX)"
git read-tree HEAD 2>/dev/null || true
git add -A >/dev/null 2>&1
tree=${'$'}(git write-tree) || exit 1
parent=""; git rev-parse -q --verify HEAD >/dev/null && parent="-p HEAD"
c=${'$'}(GIT_AUTHOR_NAME=Aura GIT_AUTHOR_EMAIL=aura@local GIT_COMMITTER_NAME=Aura GIT_COMMITTER_EMAIL=aura@local git commit-tree ${'$'}tree ${'$'}parent -m "aura checkpoint ${'$'}AURA_CP_ID") || exit 1
git update-ref refs/aura/cp-${'$'}AURA_CP_ID ${'$'}c
rm -f "${'$'}GIT_INDEX_FILE"
echo "CP ${'$'}c"
""".trimStart()

        val RESTORE = """
git cat-file -e "${'$'}AURA_SHA" 2>/dev/null || { echo "MISSING_SNAPSHOT"; exit 1; }
git read-tree -u --reset "${'$'}AURA_SHA" && git clean -fdq && git reset -q && echo RESTORED
""".trimStart()
    }
}

