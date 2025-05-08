package com.liuhao.doubleclicktoclosetab

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile

// Logger for the Initializer itself to test if it's being called
private val LOG_INITIALIZER_TEST = Logger.getInstance("#com.liuhao.doubleclickclosetab.TabListenerLogic")

// Logger for the actual tab listener logic (can keep the old name or change it)
private val LOG_TAB_LISTENER = Logger.getInstance("#com.liuhao.doubleclickclosetab.TabListenerLogic")
private val LOG = Logger.getInstance("#com.liuhao.doubleclickclosetab.TabListenerLogic")

class DoubleClickTabCloserInitializer : ProjectActivity {

    // This init block logs when an instance of this class is created by the IDE.
    init {
        LOG_INITIALIZER_TEST.warn("<<<<< DoubleClickTabCloserInitializer INSTANCE CREATED (ProjectActivity) >>>>>")
        System.err.println("<<<<< DoubleClickTabCloserInitializer INSTANCE CREATED (ProjectActivity) via System.err >>>>>")
    }

    override suspend fun execute(project: Project) {
        // This logs if the execute method is actually called.
        LOG_INITIALIZER_TEST.warn("<<<<< DoubleClickTabCloserInitializer EXECUTE CALLED for project: ${project.name} >>>>>")
        System.err.println("<<<<< DoubleClickTabCloserInitializer EXECUTE CALLED via System.err for project: ${project.name} >>>>>")

        // --- Original logic from previous correct version ---
        LOG_TAB_LISTENER.info("Initializing DoubleClickTabCloser logic for project: ${project.name}")

        val listener = TabDoubleClickListener(project)

        Toolkit.getDefaultToolkit().addAWTEventListener(
            listener,
            AWTEvent.MOUSE_EVENT_MASK
        )

        Disposer.register(project) {
            LOG_TAB_LISTENER.info("Disposing DoubleClickTabCloser logic for project: ${project.name}. Removing AWTEventListener.")
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
        }
        LOG_TAB_LISTENER.info("DoubleClickTabCloser AWTEventListener registered for project: ${project.name}")
        // --- End of original logic ---
    }
}

// TabDoubleClickListener class (from the last working version after fixing compilation errors)
private class TabDoubleClickListener(private val project: Project) : AWTEventListener {

    // Helper to convert MouseEvent ID to a readable string
    private fun mouseEventIdToString(id: Int): String {
        return when (id) {
            MouseEvent.MOUSE_CLICKED -> "MOUSE_CLICKED"
            MouseEvent.MOUSE_PRESSED -> "MOUSE_PRESSED"
            MouseEvent.MOUSE_RELEASED -> "MOUSE_RELEASED"
            MouseEvent.MOUSE_MOVED -> "MOUSE_MOVED"
            MouseEvent.MOUSE_ENTERED -> "MOUSE_ENTERED"
            MouseEvent.MOUSE_EXITED -> "MOUSE_EXITED"
            MouseEvent.MOUSE_DRAGGED -> "MOUSE_DRAGGED"
            MouseEvent.MOUSE_WHEEL -> "MOUSE_WHEEL"
            else -> "UNKNOWN_MOUSE_EVENT_ID_$id"
        }
    }

    override fun eventDispatched(event: AWTEvent?) {
        if (event !is MouseEvent) {
            return
        }

        // Log ALL mouse events this listener receives, before any filtering
        // This is crucial for debugging the clickCount issue
        LOG.info(
            "RAW MOUSE EVENT: ID=${mouseEventIdToString(event.id)}, " +
            "ClickCount=${event.clickCount}, Button=${event.button}, " +
            "Component=${event.component?.javaClass?.name}, " +
            "isLeftButton=${SwingUtilities.isLeftMouseButton(event)}, " +
            "isPopupTrigger=${event.isPopupTrigger}"
        )

        // We will now look for MOUSE_PRESSED with clickCount == 2
        if (event.id != MouseEvent.MOUSE_PRESSED) {
            return // Only interested in MOUSE_PRESSED for this strategy
        }

        if (!SwingUtilities.isLeftMouseButton(event) || event.clickCount != 2) {
            // Log why we are returning if the condition isn't met
            // LOG.info("MOUSE_PRESSED event did not meet criteria: isLeft=${SwingUtilities.isLeftMouseButton(event)}, clickCount=${event.clickCount}")
            return
        }

        LOG.info("Candidate MOUSE_PRESSED event for double-click: Component=${event.component?.javaClass?.simpleName}")

        val component = event.component as? JComponent ?: run {
            LOG.warn("Event component is null or not a JComponent. Ignoring.")
            return
        }

        val dataContext: DataContext = com.intellij.ide.DataManager.getInstance().getDataContext(component)
        val virtualFileFromContext: VirtualFile? = dataContext.getData(CommonDataKeys.VIRTUAL_FILE)
        val currentProjectFromContext: Project? = dataContext.getData(CommonDataKeys.PROJECT)

        LOG.info("DataContext info: Project from context='${currentProjectFromContext?.name}', Listener's project='${project.name}'")
        LOG.info("DataContext info: VIRTUAL_FILE='${virtualFileFromContext?.name}'")

        if (currentProjectFromContext != project) {
            LOG.debug("Ignoring event: Project from DataContext ('${currentProjectFromContext?.name}') doesn't match listener's project ('${project.name}').")
            return
        }

        val fileToClose: VirtualFile? = virtualFileFromContext

        if (fileToClose != null) {
            LOG.info("File to potentially close from MOUSE_PRESSED: ${fileToClose.name} in project ${project.name}")

            var currentComponent: Component? = component
            var isLikelyTabClick = false
            for (i in 0..6) {
                if (currentComponent == null) break
                val className = currentComponent.javaClass.name
                if (className.contains("TabLabel") ||
                    className.contains("EditorTab") ||
                    className.contains("EditorTabs") ||
                    className.contains("JBTabsImpl") ||
                    className.contains("SingleHeightTabs")
                ) {
                    isLikelyTabClick = true
                    LOG.info("Heuristic pass: Clicked component or ancestor '${className}' suggests a tab UI element.")
                    break
                }
                currentComponent = currentComponent.parent
            }

            if (!isLikelyTabClick) {
                LOG.info("Heuristic fail: MOUSE_PRESSED with a file ('${fileToClose.name}') occurred on component '${component.javaClass.name}', but it or its direct ancestors don't look like a known tab UI element. Ignoring.")
                return
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    LOG.warn("Project '${project.name}' is disposed. Cannot close file '${fileToClose.name}'.")
                    return@invokeLater
                }
                val fileEditorManager = FileEditorManager.getInstance(project)
                if (fileEditorManager.isFileOpen(fileToClose)) {
                    LOG.info("Attempting to close file: ${fileToClose.name}")
                    fileEditorManager.closeFile(fileToClose)
                    // Consume the event *if possible and makes sense*.
                    // For AWTEventListener, consuming the original event is tricky as it's already dispatched.
                    // event.consume() // Might not have the desired effect here.
                    LOG.info("File close command issued for: ${fileToClose.name}")
                } else {
                    LOG.info("File '${fileToClose.name}' is no longer open or was not recognized by FileEditorManager.")
                }
            }
        } else {
            LOG.info("No VirtualFile found in DataContext for the MOUSE_PRESSED event on component: ${component.javaClass.name}. Cannot determine file to close.")
        }
    }
}