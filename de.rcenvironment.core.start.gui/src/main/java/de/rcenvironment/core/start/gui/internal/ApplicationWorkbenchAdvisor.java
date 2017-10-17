/*
 * Copyright (C) 2006-2016 DLR, Germany
 * 
 * All rights reserved
 * 
 * http://www.rcenvironment.de/
 */

package de.rcenvironment.core.start.gui.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPartListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.application.IWorkbenchConfigurer;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchAdvisor;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.navigator.resources.ProjectExplorer;

import de.rcenvironment.core.configuration.ConfigurationService;
import de.rcenvironment.core.gui.utils.incubator.ContextMenuItemRemover;
import de.rcenvironment.core.start.Application;
import de.rcenvironment.core.utils.common.StringUtils;

/**
 * This class advises the creation of the workbench of the {@link Application}.
 * 
 * @author Christian Weiss
 */
public class ApplicationWorkbenchAdvisor extends WorkbenchAdvisor {

    private static final String READ_ONLY_WF_EDITOR_ID = "de.rcenvironment.core.gui.workflow.editor.WorkflowRunEditor";

    private static final int MINIMUM_HEIGHT = 250;

    private static final int MINIMUM_WIDTH = 500;

    private static final Log LOGGER = LogFactory.getLog(ApplicationWorkbenchAdvisor.class);

    private static ConfigurationService configService;

    private static String windowTitle = "%s (%s)";
    
    public ApplicationWorkbenchAdvisor() {}

    protected void bindConfigurationService(final ConfigurationService configServiceIn) {
        configService = configServiceIn;
    }

    @Override
    public WorkbenchWindowAdvisor createWorkbenchWindowAdvisor(IWorkbenchWindowConfigurer configurer) {
        IWorkbenchWindowConfigurer windowConfigurer = configurer;
        String platformName = configService.getInstanceName();
        String appName = Platform.getProduct().getName();
        if (platformName != null && appName != null) {
            windowConfigurer.setTitle(StringUtils.format(windowTitle, appName, platformName));
        }
        return new ApplicationWorkbenchWindowAdvisor(configurer);
    }

    @Override
    public String getInitialWindowPerspectiveId() {
        return "de.rcenvironment.rce";
    }

    @Override
    public void initialize(IWorkbenchConfigurer configurer) {
        super.initialize(configurer);
        configurer.setSaveAndRestore(true);
        WorkbenchAdvisorDelegate.declareWorkbenchImages(getWorkbenchConfigurer());
    }

    @Override
    public void preStartup() {
        super.preStartup();
        // required to be able to use the Resource view
        IDE.registerAdapters();
    }

    @Override
    public void postStartup() {
        super.postStartup();

        Display.getDefault().asyncExec(new Runnable() {

            @Override
            public void run() {

                // refreshes the Resource Explorer, otherwise projects will not be shown
                IWorkbenchWindow[] workbenchs =
                    PlatformUI.getWorkbench().getWorkbenchWindows();
                ProjectExplorer view = null;
                for (IWorkbenchWindow workbench : workbenchs) {
                    for (IWorkbenchPage page : workbench.getPages()) {
                        view = (ProjectExplorer)
                            page.findView("org.eclipse.ui.navigator.ProjectExplorer");
                        break;
                    }
                }

                if (view == null) {
                    LOGGER.warn("Project Explorer could not be found at startup so its resources could not be refreshed automatically.");
                    return;
                }
                view.getCommonViewer().setInput(ResourcesPlugin.getWorkspace().getRoot());

                // remove unwanted menu entries from project explorer's context menu
                ContextMenuItemRemover.removeUnwantedMenuEntries(view.getCommonViewer().getControl());

                UnwantedUIRemover.removeUnwantedNewWizards();
                UnwantedUIRemover.removeUnwantedExportWizards();
                UnwantedUIRemover.removeUnwantedImportWizards();
                UnwantedUIRemover.removeUnWantedPerspectives();
                UnwantedUIRemover.removeUnWantedViews();
                EclipsePreferencesUIOrganizer.removeUnwantetPreferencePages();

                // setting minimum size for the whole RCE window
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell().setMinimumSize(MINIMUM_WIDTH, MINIMUM_HEIGHT);

                // register listener to programmatically set project explorer's resources when closing and reopening it
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getPartService().addPartListener(new WorkbenchWindowPartListener());
            }
        });
    }
    
    

   

    @Override
    public boolean preShutdown() {

        try {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            workspace.save(true, null);
        } catch (CoreException e) {
            // swallow
            @SuppressWarnings("unused") int i = 0;
        } catch (RuntimeException e) {
            // swallow
            @SuppressWarnings("unused") int i = 0;
        }
        return super.preShutdown();
    }

    /**
     * PartListener for WorkbenchWindow making sure the ProjectExplorer shows resources on reopen properly.
     *
     * @author Oliver Seebach
     */
    private final class WorkbenchWindowPartListener implements IPartListener {

        @Override
        public void partOpened(IWorkbenchPart workbenchPart) {
            if (workbenchPart instanceof ProjectExplorer) {
                ((ProjectExplorer) workbenchPart).getCommonViewer().setInput(ResourcesPlugin.getWorkspace().getRoot());
            }
        }

        @Override
        public void partDeactivated(IWorkbenchPart workbenchPart) {}

        @Override
        public void partClosed(IWorkbenchPart workbenchPart) {}

        @Override
        public void partBroughtToTop(IWorkbenchPart workbenchPart) {}

        @Override
        public void partActivated(IWorkbenchPart workbenchPart) {}
    }

}