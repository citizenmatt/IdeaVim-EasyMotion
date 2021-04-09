/*
 * IdeaVim-EasyMotion. Easymotion emulator plugin for IdeaVim.
 * Copyright (C) 2019-2020  Alex Plate
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.jetbrains.plugins.extension.easymotion

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.PlatformTestUtil
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.command.CommandState
import com.maddyhome.idea.vim.command.CommandState.SubMode.VISUAL_CHARACTER
import com.maddyhome.idea.vim.command.CommandState.SubMode.VISUAL_LINE
import com.maddyhome.idea.vim.extension.VimExtensionHandler
import com.maddyhome.idea.vim.group.visual.vimSetSelection
import com.maddyhome.idea.vim.helper.inVisualMode
import com.maddyhome.idea.vim.helper.vimSelectionStart
import org.acejump.action.AceAction
import org.acejump.session.AceJumpListener
import org.acejump.session.Session
import org.acejump.session.SessionManager

/**
 * In order to implement an easymotion command you should implement [HandlerProcessor] and pass it to one of
 *   implementations of [EasyHandlerBase]
 */
abstract class HandlerProcessor(val motionType: MotionType) {
    /** This function is called right after [AceAction] execution */
    open fun customization(editor: Editor, session: Session) {}

    /** This function is called right after user finished to work with AceJump/EasyMotion */
    open fun onFinish(editor: Editor, query: String?) {}
}

/** Standard handled that is used in real work. For tests [TestObject.TestHandler] is used */
class StandardHandler(processor: HandlerProcessor) : EasyHandlerBase(processor) {
    override fun execute(editor: Editor, context: DataContext) {

        beforeAction(editor)

        val session = SessionManager.start(editor)

        session.addAceJumpListener(object : AceJumpListener {
            override fun finished(mark: String?, query: String?) {
                finish(editor, query)
                session.removeAceJumpListener(this)
            }
        })

        rightAfterAction(editor, session)
    }
}

/** Object that contains test related staff */
object TestObject {
    var handlerWasCalled = false

    var handler: (editorText: String, jumpLocations: List<Int>) -> Unit = { _, _ -> }
    var inputQuery: (Session) -> String = { "" }

    /** Handler that is used during unit tests */
    class TestHandler(processor: HandlerProcessor) : EasyHandlerBase(processor) {
        override fun execute(editor: Editor, context: DataContext) {
            handlerWasCalled = true

            beforeAction(editor)

            val session = SessionManager.start(editor)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            rightAfterAction(editor, session)

            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val query = inputQuery(session)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            finish(editor, query)

            handler(editor.document.text, session.tags.map { it.value })
        }
    }
}

abstract class EasyHandlerBase(private val processor: HandlerProcessor) : VimExtensionHandler {

    private var startSelection: Int? = null
    private var initialOffset: Int? = null

    protected fun beforeAction(editor: Editor) {
        startSelection = if (editor.inVisualMode) editor.caretModel.currentCaret.vimSelectionStart else null
        initialOffset = editor.caretModel.currentCaret.offset
    }

    protected fun rightAfterAction(editor: Editor, session: Session) {
        // Add position to jump list
        VimPlugin.getMark().saveJumpLocation(editor)
        processor.customization(editor, session)
        ResetAction.register(editor)
    }

    protected fun finish(editor: Editor, query: String?) {
        processor.onFinish(editor, query)
        startSelection?.let {
            editor.caretModel.currentCaret.vimSetSelection(it, editor.caretModel.offset, false)
        }

        // Inclusive / Exclusive / Linewise for op mode
        val myInitialOffset = initialOffset
        if (myInitialOffset != null && CommandState.getInstance(editor).isOperatorPending) {
            val selectionType = when (processor.motionType) {
                MotionType.LINE -> VISUAL_LINE
                MotionType.INCLUSIVE -> VISUAL_CHARACTER
                MotionType.BIDIRECTIONAL_INCLUSIVE -> {
                    if (myInitialOffset < editor.caretModel.currentCaret.offset) VISUAL_CHARACTER else null
                }
                else -> null
            }
            if (selectionType != null) {
                VimPlugin.getVisualMotion().enterVisualMode(editor, selectionType)
                editor.caretModel.currentCaret.vimSetSelection(myInitialOffset, editor.caretModel.currentCaret.offset)
            }
        }

        // Remove position from jumps list if caret haven't moved
        if (myInitialOffset == editor.caretModel.offset) {
            VimPlugin.getMark().jumps.dropLast(1)
        }

        ResetAction.unregister(editor)

        initialOffset = null
    }
}

enum class MotionType {
    INCLUSIVE,
    EXCLUSIVE,
    BIDIRECTIONAL_INCLUSIVE,
    LINE
}
