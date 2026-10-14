/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.activities.agent

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.AIAgentManager
import java.io.File
import kotlinx.coroutines.launch

/**
 * Agent Room — ruang kerja otonom agent (Lovable-style).
 *
 * Flow: instruksi user → PLAN (agent susun langkah) → ACT (eksekusi per langkah,
 * file nyata + tool calls) → VERIFY (build_project bila diminta) → REPORT.
 * Semua langkah tampil real-time di timeline; Stop membatalkan di antara langkah.
 */
class AgentRoomActivity : AppCompatActivity() {

    companion object {
        private const val MAX_STEPS = 10
        private const val PLAN_MARKER = "PLAN_STEPS:"
    }

    private lateinit var timelineContainer: LinearLayout
    private lateinit var roomScroll: NestedScrollView
    private lateinit var roomStatus: TextView
    private lateinit var roomProgress: CircularProgressIndicator
    private lateinit var roomInput: TextInputEditText
    private lateinit var startBtn: MaterialButton
    private lateinit var stopBtn: MaterialButton

    @Volatile private var cancelled = false
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent_room)

        timelineContainer = findViewById(R.id.timelineContainer)
        roomScroll = findViewById(R.id.roomScroll)
        roomStatus = findViewById(R.id.roomStatus)
        roomProgress = findViewById(R.id.roomProgress)
        roomInput = findViewById(R.id.roomInput)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)

        startBtn.setOnClickListener { startRun() }
        stopBtn.setOnClickListener { stopRun() }
    }

    private fun startRun() {
        val instruction = roomInput.text?.toString()?.trim().orEmpty()
        if (instruction.isEmpty()) {
            roomStatus.text = "Tulis instruksi dulu."
            return
        }
        if (running) return

        running = true
        cancelled = false
        startBtn.isEnabled = false
        stopBtn.visibility = View.VISIBLE
        roomProgress.visibility = View.VISIBLE
        timelineContainer.removeAllViews()
        roomInput.setText("")

        lifecycleScope.launch { run(instruction) }
    }

    private fun stopRun() {
        cancelled = true
        roomStatus.text = "Menghentikan… (berhenti di antara langkah)"
    }

    private fun finishRun(status: String) {
        running = false
        cancelled = false
        startBtn.isEnabled = true
        stopBtn.visibility = View.GONE
        roomProgress.visibility = View.GONE
        roomStatus.text = status
    }

    private suspend fun run(instruction: String) {
        val agent = AIAgentManager(this)
        val projectDir = try {
            com.tom.rv2ide.projects.IProjectManager.getInstance().projectDir
        } catch (e: Exception) {
            null
        }
        val planFile = projectDir?.let { File(it, "agent_plan.md") }

        addTimeline("🧭", "PLAN", "Menyusun rencana dari instruksi…")

        // ---- Fase 1: PLAN — minta agent pecah instruksi jadi langkah bernomor ----
        val planResult = java.util.concurrent.atomic.AtomicReference("")

        val planPrompt = buildString {
            append("Anda adalah agent developer Android di dalam IDE. ");
            append("Pecah tugas berikut menjadi langkah-langkah konkret bernomor (maks $MAX_STEPS). ");
            append("Setiap langkah harus bisa dikerjakan dengan mengubah/membuat file di project.\n\n");
            append("TUGAS:\n$instruction\n\n");
            append("Format jawaban WAJIB:\n");
            append("$PLAN_MARKER\n1. ...\n2. ...\n(lalu penjelasan singkat)")
        }

        val planResponse = agent.executeRequest(planPrompt, object : AIAgentManager.AIAgentCallback {
            override fun onProcessing(message: String) {}
            override fun onFileModifying(filePath: String, fileName: String) {}
            override fun onFileModified(filePath: String, fileName: String, success: Boolean) {}
            override fun onSuccess(response: String, modifications: List<AIAgentManager.ModificationResult>, summary: AIAgentManager.ModificationSummary) {}
            override fun onTextResponse(response: String, summary: AIAgentManager.ModificationSummary) {
                planResult.set(response)
            }
            override fun onError(message: String) {}
            override fun onRetry(attemptNumber: Int, message: String) {}
        })
        val planResponseText = planResult.get()

        if (cancelled) { finishRun("Dihentikan saat planning."); return }

        val steps = parseSteps(planResponseText)
        if (steps.isEmpty()) {
            // Agent tidak memberi rencana terstruktur — tampilkan respons mentah & berhenti.
            addTimeline("⚠️", "PLAN", planResponseText.take(1200))
            finishRun("Gagal menyusun rencana. Coba rumuskan instruksi lebih spesifik.")
            return
        }

        planFile?.writeText(buildPlanDoc(instruction, steps))
        addTimeline("📋", "PLAN", "${steps.size} langkah:\n" + steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"))

        // ---- Fase 2: ACT — eksekusi per langkah ----
        val results = mutableListOf<Pair<Int, String>>()
        for ((index, step) in steps.withIndex()) {
            if (cancelled) {
                results.forEach { (n, r) -> addTimeline(if (r == "OK") "✅" else "❌", "ACT", "Langkah $n: $r") }
                finishRun("Dihentikan. ${results.count { it.second == "OK" }}/${steps.size} langkah selesai.")
                return
            }

            roomStatus.text = "ACT ${index + 1}/${steps.size}: ${step.take(60)}"
            addTimeline("🔧", "ACT ${index + 1}/${steps.size}", step)

            val stepPrompt = buildString {
                append("Kerjakan HANYA langkah berikut dari rencana. Jangan mengerjakan langkah lain.\n\n");
                append("TUGAS BESAR:\n$instruction\n\nLANGKAH ${index + 1}:\n$step\n\n");
                append("Ubah/buat file yang diperlukan dengan format FILE_TO_MODIFY seperti biasa.")
            }

            var stepResult = "gagal: tidak ada respons"
            val stepFlag = java.util.concurrent.atomic.AtomicReference<String?>(null)
            try {
                agent.executeRequest(stepPrompt, object : AIAgentManager.AIAgentCallback {
                    override fun onProcessing(message: String) {}
                    override fun onFileModifying(filePath: String, fileName: String) {
                        addTimeline("📝", "ACT ${index + 1}", "Menulis $fileName")
                    }
                    override fun onFileModified(filePath: String, fileName: String, success: Boolean) {
                        if (success) stepFlag.set("OK")
                    }
                    override fun onSuccess(response: String, modifications: List<AIAgentManager.ModificationResult>, summary: AIAgentManager.ModificationSummary) {
                        if (summary.successfulFiles > 0) stepFlag.set("OK")
                    }
                    override fun onTextResponse(response: String, summary: AIAgentManager.ModificationSummary) {}
                    override fun onError(message: String) {
                        stepFlag.set("gagal: ${message.take(200)}")
                    }
                    override fun onRetry(attemptNumber: Int, message: String) {}
                })
            } catch (e: Exception) {
                stepFlag.set("gagal: ${e.message?.take(200)}")
            }

            stepFlag.get()?.let { stepResult = it }
            results.add(index + 1 to stepResult)
            addTimeline(if (stepResult == "OK") "✅" else "❌", "ACT ${index + 1}", stepResult)
        }

        if (cancelled) { finishRun("Dihentikan setelah eksekusi."); return }

        // ---- Fase 3: VERIFY — minta agent build bila ada langkah kode yang sukses ----
        val okCount = results.count { it.second == "OK" }
        if (okCount > 0) {
            addTimeline("🔨", "VERIFY", "Menjalankan build untuk verifikasi…")
            roomStatus.text = "VERIFY: build_project…"
            try {
                agent.executeRequest(
                    "Jalankan tool build_project untuk memverifikasi perubahan yang baru saja dibuat. " +
                        "Setelah hasil build keluar, ringkas apakah BUILD SUCCESSFUL atau gagal beserta penyebabnya.",
                    object : AIAgentManager.AIAgentCallback {
                        override fun onProcessing(message: String) {}
                        override fun onFileModifying(filePath: String, fileName: String) {}
                        override fun onFileModified(filePath: String, fileName: String, success: Boolean) {}
                        override fun onSuccess(response: String, m: List<AIAgentManager.ModificationResult>, s: AIAgentManager.ModificationSummary) {
                            addTimeline("🔨", "VERIFY", response.take(800))
                        }
                        override fun onTextResponse(response: String, s: AIAgentManager.ModificationSummary) {
                            addTimeline("🔨", "VERIFY", response.take(800))
                        }
                        override fun onError(message: String) {
                            addTimeline("⚠️", "VERIFY", message.take(400))
                        }
                        override fun onRetry(attemptNumber: Int, message: String) {}
                    }
                )
            } catch (e: Exception) {
                addTimeline("⚠️", "VERIFY", "Build tidak bisa dijalankan: ${e.message?.take(200)}")
            }
        }

        // ---- Fase 4: REPORT ----
        addTimeline("📊", "REPORT", "$okCount/${steps.size} langkah berhasil. Rencana tersimpan di agent_plan.md.")
        finishRun(if (okCount == steps.size) "✅ Selesai — semua langkah sukses." else "⚠️ Selesai — $okCount/${steps.size} langkah sukses.")
    }

    private fun parseSteps(response: String): List<String> {
        if (!response.contains(PLAN_MARKER)) return emptyList()
        val after = response.substringAfter(PLAN_MARKER)
        return after.lines()
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.matches(Regex("^\\d+[.)].*")) || (it.isNotEmpty() && it.length > 4) }
            .map { it.replace(Regex("^\\d+[.)]\\s*"), "") }
            .filter { it.length > 4 }
            .take(MAX_STEPS)
    }

    private fun buildPlanDoc(instruction: String, steps: List<String>): String = buildString {
        append("# Agent Plan\n\n")
        append("Dibuat otomatis oleh Agent Room.\n\n## Instruksi\n$instruction\n\n## Langkah\n")
        steps.forEachIndexed { i, s -> append("${i + 1}. $s\n") }
    }

    private fun addTimeline(icon: String, phase: String, text: String) {
        runOnUiThread {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
            }
            val iconView = TextView(this).apply {
                this.text = icon
                textSize = 16f
                setPadding(0, 0, 24, 0)
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val phaseView = TextView(this).apply {
                this.text = phase
                textSize = 12f
                setTextColor(androidx.core.content.ContextCompat.getColor(context, com.google.android.material.R.color.design_default_color_primary))
            }
            val textView = TextView(this).apply {
                this.text = text
                textSize = 14f
                setTextIsSelectable(true)
            }
            col.addView(phaseView)
            col.addView(textView)
            row.addView(iconView)
            row.addView(col)
            timelineContainer.addView(row)
            roomScroll.post { roomScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
