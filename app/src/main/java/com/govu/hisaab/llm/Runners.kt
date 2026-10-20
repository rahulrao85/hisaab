package com.govu.hisaab.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.Closeable
import java.io.File

/** One prompt in, one answer out, greedy decoding, fresh context every time. */
internal interface Runner : Closeable {
    fun ask(prompt: String): String
}

/** Gemma 3 1B `.task` files, through MediaPipe LLM Inference. */
internal class MediaPipeRunner(ctx: Context, file: File, gpu: Boolean) : Runner {
    private val llm = LlmInference.createFromOptions(
        ctx,
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(768)
            .setMaxTopK(40)
            .setPreferredBackend(if (gpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU)
            .build(),
    )

    override fun ask(prompt: String): String {
        val opts = LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(1).setTemperature(0f).setRandomSeed(7).build()
        LlmInferenceSession.createFromOptions(llm, opts).use { s ->
            // Gemma's chat turns. Without them the 1B model just continues the text,
            // which in the first test meant echoing the instructions back.
            s.addQueryChunk("<start_of_turn>user\n${prompt.trim()}<end_of_turn>\n<start_of_turn>model\n")
            return s.generateResponse().replace("<end_of_turn>", "").trim()
        }
    }

    override fun close() = llm.close()
}

/** Gemma 4 `.litertlm` files, through LiteRT-LM, which applies the chat template itself. */
internal class LiteRtLmRunner(ctx: Context, file: File, gpu: Boolean) : Runner {
    private val engine = Engine(
        EngineConfig(
            modelPath = file.absolutePath,
            backend = if (gpu) Backend.GPU() else Backend.CPU(),
            maxNumTokens = 1024,
            cacheDir = ctx.cacheDir.absolutePath,
        ),
    ).also { it.initialize() }

    override fun ask(prompt: String): String {
        val cfg = ConversationConfig(
            samplerConfig = SamplerConfig(1, 1.0, 0.0, 7),
            maxOutputToken = 64,
            thinkingConfig = ThinkingConfig(false),
        )
        engine.createConversation(cfg).use { c ->
            val reply = c.sendMessage(prompt.trim())
            return reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
        }
    }

    override fun close() = engine.close()
}
