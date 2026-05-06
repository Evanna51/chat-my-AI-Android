package com.example.aichat

import android.app.Activity
import android.app.AlertDialog
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText

/**
 * 章节计划编辑对话框：复用 [R.layout.dialog_chapter_plan]。
 * 由 SessionOutlineActivity 在生成模型计划后弹出，用户可编辑并保存到大纲。
 */
object ChapterPlanDialog {

    interface Callback {
        fun onCancel()
        /** edited 不为 null 且 hasAnyContent 为 true 时调用。 */
        fun onSave(edited: ChapterPlanDraft)
    }

    class Controller internal constructor(
        var dialog: AlertDialog?,
        private val textStatus: TextView?,
        val editGoal: TextInputEditText?,
        val editStart: TextInputEditText?,
        val editEnd: TextInputEditText?,
        val editDrives: TextInputEditText?,
        val editKnowledge: TextInputEditText?,
        val editEvents: TextInputEditText?,
        val editForeshadow: TextInputEditText?,
        val editPayoff: TextInputEditText?,
        val editForbidden: TextInputEditText?,
        val editStyle: TextInputEditText?,
        val editLength: TextInputEditText?,
    ) {
        fun setStatus(status: String?) {
            val text = status?.trim().orEmpty()
            textStatus?.text = if (text.isEmpty()) "正在生成章节计划…" else text
        }

        fun applyDraft(draft: ChapterPlanDraft, fillOnlyEmpty: Boolean) {
            applyText(editGoal, draft.chapterGoal, fillOnlyEmpty)
            applyText(editStart, draft.startState, fillOnlyEmpty)
            applyText(editEnd, draft.endState, fillOnlyEmpty)
            applyText(editDrives, draft.characterDrivesToMultiline(), fillOnlyEmpty)
            applyText(editKnowledge, joinLines(draft.knowledgeBoundary), fillOnlyEmpty)
            applyText(editEvents, joinLines(draft.eventChain), fillOnlyEmpty)
            applyText(editForeshadow, joinLines(draft.foreshadow), fillOnlyEmpty)
            applyText(editPayoff, joinLines(draft.payoff), fillOnlyEmpty)
            applyText(editForbidden, joinLines(draft.forbidden), fillOnlyEmpty)
            applyText(editStyle, draft.styleGuide, fillOnlyEmpty)
            applyText(editLength, draft.targetLength, fillOnlyEmpty)
        }

        fun collectDraft(): ChapterPlanDraft {
            val draft = ChapterPlanDraft()
            draft.chapterGoal = textOf(editGoal)
            draft.startState = textOf(editStart)
            draft.endState = textOf(editEnd)
            draft.characterDrives = ChapterPlanDraft.parseCharacterDrives(textOf(editDrives))
            draft.knowledgeBoundary = parseLines(textOf(editKnowledge))
            draft.eventChain = parseLines(textOf(editEvents))
            draft.foreshadow = parseLines(textOf(editForeshadow))
            draft.payoff = parseLines(textOf(editPayoff))
            draft.forbidden = parseLines(textOf(editForbidden))
            draft.styleGuide = textOf(editStyle)
            draft.targetLength = textOf(editLength)
            return draft
        }

        private fun applyText(edit: TextInputEditText?, value: String?, fillOnlyEmpty: Boolean) {
            if (edit == null) return
            val incoming = value.orEmpty()
            if (fillOnlyEmpty) {
                val current = edit.text?.toString()?.trim().orEmpty()
                if (current.isNotEmpty()) return
            }
            edit.setText(incoming)
        }
    }

    fun show(
        activity: Activity,
        title: String,
        initial: ChapterPlanDraft,
        initialStatus: String,
        callback: Callback,
    ): Controller {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_chapter_plan, null)
        view.findViewById<TextView>(R.id.textPlanDialogTitle).text = title
        val controller = Controller(
            null,
            view.findViewById(R.id.textPlanGenerationStatus),
            view.findViewById(R.id.editPlanChapterGoal),
            view.findViewById(R.id.editPlanStartState),
            view.findViewById(R.id.editPlanEndState),
            view.findViewById(R.id.editPlanCharacterDrives),
            view.findViewById(R.id.editPlanKnowledgeBoundary),
            view.findViewById(R.id.editPlanEventChain),
            view.findViewById(R.id.editPlanForeshadow),
            view.findViewById(R.id.editPlanPayoff),
            view.findViewById(R.id.editPlanForbidden),
            view.findViewById(R.id.editPlanStyleGuide),
            view.findViewById(R.id.editPlanTargetLength),
        )
        controller.applyDraft(initial, false)
        controller.setStatus(initialStatus)

        // 透明 window + 自定义布局，让 bg_glass_dialog 的圆角/阴影/高光生效（与 ChapterTargetPicker 同模式）。
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<View>(R.id.btnPlanCancel).setOnClickListener {
            callback.onCancel()
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btnPlanSave).setOnClickListener {
            val edited = controller.collectDraft()
            if (!edited.hasAnyContent()) callback.onCancel() else callback.onSave(edited)
            dialog.dismiss()
        }
        dialog.show()
        controller.dialog = dialog
        return controller
    }

    private fun textOf(edit: TextInputEditText?): String {
        val raw = edit?.text?.toString() ?: return ""
        return raw.trim()
    }

    private fun parseLines(text: String): List<String> {
        if (TextUtils.isEmpty(text.trim())) return emptyList()
        return text.split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun joinLines(items: List<String>?): String {
        if (items.isNullOrEmpty()) return ""
        return items.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
