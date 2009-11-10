/*******************************************************************************
 * Copyright (c) 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.internal.p2.ui.dialogs;

import java.util.ArrayList;
import org.eclipse.equinox.internal.p2.ui.*;
import org.eclipse.equinox.internal.p2.ui.model.*;
import org.eclipse.equinox.internal.provisional.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.operations.*;
import org.eclipse.equinox.p2.ui.ProvisioningUI;

/**
 * An Install wizard that is invoked when the user has already selected which
 * IUs should be installed and does not need to browse the available software.
 * 
 * @since 3.5
 */
public class PreselectedIUInstallWizard extends WizardWithLicenses {

	QueryableMetadataRepositoryManager manager;

	public PreselectedIUInstallWizard(ProvisioningUI ui, InstallOperation operation, IInstallableUnit[] initialSelections, PreloadMetadataRepositoryJob job) {
		super(ui, operation, initialSelections, job);
		setWindowTitle(ProvUIMessages.InstallIUOperationLabel);
		setDefaultPageImageDescriptor(ProvUIImages.getImageDescriptor(ProvUIImages.WIZARD_BANNER_INSTALL));
	}

	protected ISelectableIUsPage createMainPage(IUElementListRoot input, Object[] selections) {
		mainPage = new SelectableIUsPage(ui, input, selections);
		mainPage.setTitle(ProvUIMessages.PreselectedIUInstallWizard_Title);
		mainPage.setDescription(ProvUIMessages.PreselectedIUInstallWizard_Description);
		((SelectableIUsPage) mainPage).updateStatus(input, operation);
		return mainPage;
	}

	protected ResolutionResultsWizardPage createResolutionPage() {
		return new InstallWizardPage(ui, root, (InstallOperation) operation);
	}

	protected void initializeResolutionModelElements(Object[] selectedElements) {
		root = new IUElementListRoot();
		ArrayList list = new ArrayList(selectedElements.length);
		ArrayList selected = new ArrayList(selectedElements.length);
		for (int i = 0; i < selectedElements.length; i++) {
			IInstallableUnit iu = ElementUtils.getIU(selectedElements[i]);
			if (iu != null) {
				AvailableIUElement element = new AvailableIUElement(root, iu, getProfileId(), getPolicy().getQueryContext().getShowProvisioningPlanChildren());
				list.add(element);
				selected.add(element);
			}
		}
		root.setChildren(list.toArray());
		planSelections = selected.toArray();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.equinox.internal.p2.ui.dialogs.ProvisioningOperationWizard#getErrorReportingPage()
	 */
	protected IResolutionErrorReportingPage getErrorReportingPage() {
		return (IResolutionErrorReportingPage) mainPage;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.equinox.internal.p2.ui.dialogs.ProvisioningOperationWizard#getProfileChangeOperation(java.lang.Object[])
	 */
	protected ProfileChangeOperation getProfileChangeOperation(Object[] elements) {
		InstallOperation op = new InstallOperation(ui.getSession(), ElementUtils.elementsToIUs(elements));
		op.setProfileId(getProfileId());
		op.setRootMarkerKey(getRootMarkerKey());
		op.setProvisioningContext(getProvisioningContext());
		return op;
	}
}