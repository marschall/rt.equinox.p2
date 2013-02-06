/*******************************************************************************
 * Copyright (c) 2013 Ericsson AB and others. All rights reserved. This
 * program and the accompanying materials are made available under the terms of
 * the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: 
 *     Ericsson AB - initial API and implementation
 ******************************************************************************/
package org.eclipse.equinox.p2.tests.sharedinstall;

import java.util.Properties;
import junit.framework.Test;
import junit.framework.TestSuite;
import org.eclipse.equinox.p2.tests.reconciler.dropins.ReconcilerTestSuite;

public class BaseChangeWithoutUserChange extends AbstractSharedInstallTest {

	public static Test suite() {
		TestSuite suite = new ReconcilerTestSuite();
		suite.setName(BaseChangeWithoutUserChange.class.getName());
		suite.addTest(new BaseChangeWithoutUserChange("testBaseChangeWithoutUserChange"));
		return suite;
	}

	public BaseChangeWithoutUserChange(String name) {
		super(name);
	}

	public void testBaseChangeWithoutUserChange() {
		assertInitialized();
		setupReadOnlyInstall();
		System.out.println(readOnlyBase);
		System.out.println(userBase);

		{
			//Run Eclipse a first time to have the user profile created
			startEclipseAsUser();
			assertProfileStatePropertiesHasKey(getUserProfileFolder(), "_simpleProfileRegistry_internal_" + getMostRecentProfileTimestampFromBase());
		}

		{ //Change the base and then verify
			installVerifierInBase();

			assertFalse(getUserBundleInfo().exists());
			assertFalse(getUserBundleInfoTimestamp().exists());

			Properties verificationProperties = new Properties();
			verificationProperties.setProperty("unexpectedBundleList", "p2TestBundle1");
			verificationProperties.setProperty("checkPresenceOfVerifier", "false");
			verificationProperties.setProperty("expectedBundleList", "org.eclipse.equinox.p2.tests.verifier");
			verificationProperties.setProperty("checkProfileResetFlag", "true");
			executeVerifier(verificationProperties);

			assertProfileStatePropertiesHasKey(getUserProfileFolder(), "_simpleProfileRegistry_internal_" + getMostRecentProfileTimestampFromBase());
		}
	}

}
