package com.aura.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.TitleMaker
import com.aura.agent.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class TitleMakerTest {
    @Test fun greetings_and_thanks_have_no_topic_but_real_requests_do() {
        for (g in listOf("hi", "Hello!", "hey bro", "namaste", "thanks", "hi, how are you", "नमस्ते भाई", "kaise ho bhai", "ok thanks")) assertTrue(g, TitleMaker.onlySmallTalk(listOf(g)))
        for (r in listOf("hi, can you fix my build", "build me a todo app", "ऐप बनाओ", "what is a coroutine")) assertFalse(r, TitleMaker.onlySmallTalk(listOf(r)))
        assertTrue(TitleMaker.onlySmallTalk(listOf("hi", "thanks"))); assertFalse(TitleMaker.onlySmallTalk(listOf("hi", "build an app")))
    }

    @Test fun the_prompt_uses_only_what_the_user_wrote_and_asks_for_topic_style_titles() {
        val p = TitleMaker.prompt(listOf("Please build me a todo app in Kotlin", "add dark mode too"), listOf("spec.pdf"))
        assertTrue(p.contains("1. Please build me a todo app in Kotlin") && p.contains("2. add dark mode too") && p.contains("Attached files: spec.pdf"))
        assertTrue(p.contains("Gemini, Claude and ChatGPT") && p.contains("2 to 7 words") && p.contains("Devanagari") && p.contains("Hinglish") && p.contains("Title Case"))
        assertFalse("the assistant's answer is not part of it", p.contains("Assistant"))
    }

    @Test fun model_output_is_cleaned_into_a_title() {
        assertEquals("Kotlin Todo App with Room", TitleMaker.clean("\"Kotlin Todo App with Room\""))
        assertEquals("Fix login crash", TitleMaker.clean("Title: **Fix login crash.**"))
        assertEquals("ऐप का लॉगिन क्रैश ठीक करना", TitleMaker.clean("ऐप का लॉगिन क्रैश ठीक करना।"))
        assertEquals("GitHub Actions APK build", TitleMaker.clean("GitHub Actions APK build\n\nThis chat is about building an APK."))
        assertEquals("one line only", "Python script to rename files", TitleMaker.clean("# Python script to rename files 📁"))
        val long = TitleMaker.clean("A very long title that keeps going on and on about many different things at once")
        assertTrue(long, long.length <= 50 && !long.endsWith(" ") && long.startsWith("A very long title"))
    }

    @Test fun answers_generic_words_and_copies_of_the_reply_are_rejected() {
        for (bad in listOf("Sure! Here is a title for you", "I can help with that", "Here are some ideas", "Chat", "New chat", "ok", "", "   ")) assertEquals(bad, "", TitleMaker.clean(bad))
        val reply = "To build a todo app in Kotlin you start with a Room database and a simple list screen"
        assertEquals("a title copied from the answer is rejected", "", TitleMaker.clean("To build a todo app in Kotlin you start", reply))
        assertEquals("Kotlin todo app", TitleMaker.clean("Kotlin todo app", reply))
    }

    @Test fun the_fallback_uses_the_users_words_without_politeness() {
        assertEquals("Build me a todo app in kotlin", TitleMaker.fallback(listOf("Please can you build me a todo app in kotlin with room and dark mode")))
        assertEquals("Fix the login crash", TitleMaker.fallback(listOf("bhai mujhe fix the login crash")))
        assertEquals("एक टू-डू ऐप बनाना है", TitleMaker.fallback(listOf("मुझे एक टू-डू ऐप बनाना है")))
        assertEquals("What is a coroutine", TitleMaker.fallback(listOf("what is a coroutine?")))
        assertEquals("Quick chat", TitleMaker.fallback(listOf("   ")))
        assertEquals("no links in a title", "Check this repo", TitleMaker.fallback(listOf("check this repo https://github.com/a/b")))
        assertTrue(TitleMaker.fallback(listOf("x ".repeat(80))).length <= 40)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ChatTitleAgentTest {
    private fun titleBodies(m: Mock) = m.bodies.filter { it.second.contains("You write chat titles") }.map { it.second }
    private fun titleOf(app: App, chat: String) = runBlocking { app.db.dao().chat(chat)!! }

    @Test fun the_title_request_contains_the_users_words_and_never_the_assistants_answer() {
        val m = Mock().start(); val app = Env.setup(m)
        m.script += m.text("Sure! SECRET-ANSWER-TEXT is what you need, with a long explanation")
        val r = Env.run(app, "Please build me a todo app in Kotlin")
        val b = titleBodies(m).single()
        assertTrue(b.contains("todo app in Kotlin")); assertFalse("no reply text in the title request", b.contains("SECRET-ANSWER-TEXT"))
        val c = titleOf(app, r.chatId); assertTrue(c.titled); assertEquals("A casual chat title for tests", c.title)
    }

    @Test fun a_greeting_waits_for_a_real_topic_and_makes_no_extra_request() {
        val m = Mock().start(); val app = Env.setup(m)
        m.script += m.text("Hey! What can I do for you?")
        val r = Env.run(app, "hi")
        assertTrue(titleBodies(m).isEmpty()); val c = titleOf(app, r.chatId); assertFalse(c.titled); assertEquals("New chat", c.title)
    }

    @Test fun an_unusable_model_answer_becomes_a_title_from_the_users_words() {
        val m = Mock().start(); val app = Env.setup(m)
        m.titleReply = "Sure! Here is a title for you"
        val r = Env.run(app, "Could you help me fix the login crash")
        val c = titleOf(app, r.chatId); assertEquals("Fix the login crash", c.title); assertTrue(c.titled)
    }

    @Test fun a_title_copied_from_the_answer_is_replaced() {
        val m = Mock().start(); val app = Env.setup(m)
        m.script += m.text("To build a todo app in Kotlin you start with a Room database and a simple list screen")
        m.titleReply = "To build a todo app in Kotlin you start"
        val r = Env.run(app, "how do I build a todo app")
        assertEquals("How do I build a todo app", titleOf(app, r.chatId).title)
    }
}
