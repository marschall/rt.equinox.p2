/*******************************************************************************
 *  Copyright (c) 2007, 2013 IBM Corporation and others.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 * 
 *  Contributors:
 *      IBM Corporation - initial API and implementation
 *      Ericsson AB (Pascal Rapicault) - Bug 397216 -[Shared] Better shared configuration change discovery 
 *******************************************************************************/
package org.eclipse.equinox.internal.simpleconfigurator;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;
import org.eclipse.equinox.internal.provisional.configurator.Configurator;
import org.eclipse.equinox.internal.simpleconfigurator.utils.*;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/*
 * SimpleConfigurator provides ways to install bundles listed in a file
 * accessible by the specified URL and expect states for it in advance without
 * actual application.
 * 
 * In every methods of SimpleConfiguration object,
 * 
 * 1. A value will be gotten by @{link BundleContext#getProperty(key) with
 * {@link SimpleConfiguratorConstants#PROP_KEY_EXCLUSIVE_INSTALLATION} as a key.
 * 2. If it equals "true", it will do exclusive installation, which means that
 * the bundles will not be listed in the specified url but installed at the time
 * of the method call except SystemBundle will be uninstalled. Otherwise, no
 * uninstallation will not be done.
 */
public class SimpleConfiguratorImpl implements Configurator {

	private static URL configurationURL = null;
	private static Object configurationLock = new Object();

	private BundleContext context;
	private ConfigApplier configApplier;
	private Bundle bundle;

	//for change detection in the base when running in shared install mode
	private static final long NO_TIMESTAMP = -1;
	public static final String BASE_TIMESTAMP_FILE_BUNDLESINFO = ".baseBundlesInfoTimestamp"; //$NON-NLS-1$
	public static final String KEY_BUNDLESINFO_TIMESTAMP = "bundlesInfoTimestamp";
	private static final String PROP_IGNORE_USER_CONFIGURATION = "eclipse.ignoreUserConfiguration"; //$NON-NLS-1$

	public SimpleConfiguratorImpl(BundleContext context, Bundle bundle) {
		this.context = context;
		this.bundle = bundle;
	}

	public URL getConfigurationURL() throws IOException {
		String specifiedURL = context.getProperty(SimpleConfiguratorConstants.PROP_KEY_CONFIGURL);
		if (specifiedURL == null)
			specifiedURL = "file:" + SimpleConfiguratorConstants.CONFIGURATOR_FOLDER + "/" + SimpleConfiguratorConstants.CONFIG_LIST;

		try {
			//If it is not a file URL use it as is
			if (!specifiedURL.startsWith("file:"))
				return new URL(specifiedURL);
		} catch (MalformedURLException e) {
			return null;
		}

		try {
			// if it is an absolute file URL, use it as is
			boolean done = false;
			URL url = null;
			String file = specifiedURL;
			while (!done) {
				// TODO what is this while loop for?  nested file:file:file: urls?
				try {
					url = Utils.buildURL(file);
					file = url.getFile();
				} catch (java.net.MalformedURLException e) {
					done = true;
				}
			}
			if (url != null && new File(url.getFile()).isAbsolute())
				return url;

			//if it is an relative file URL, then resolve it against the configuration area
			// TODO Support relative file URLs when not on Equinox
			URL[] configURL = EquinoxUtils.getConfigAreaURL(context);
			if (configURL != null) {
				File userConfig = new File(configURL[0].getFile(), url.getFile());
				if (configURL.length == 1)
					return userConfig.exists() ? userConfig.toURL() : null;

				File sharedConfig = new File(configURL[1].getFile(), url.getFile());
				if (!userConfig.exists())
					return sharedConfig.exists() ? sharedConfig.toURL() : null;

				if (!sharedConfig.exists())
					return userConfig.toURL();

				if (Boolean.TRUE.toString().equals(System.getProperty(PROP_IGNORE_USER_CONFIGURATION)))
					return sharedConfig.toURL();

				long sharedBundlesInfoTimestamp = getCurrentBundlesInfoBaseTimestamp(sharedConfig);
				long lastKnownBaseTimestamp = getLastKnownBundlesInfoBaseTimestamp(userConfig.getParentFile());

				if (lastKnownBaseTimestamp == sharedBundlesInfoTimestamp || lastKnownBaseTimestamp == NO_TIMESTAMP) {
					return userConfig.toURL();
				} else {
					System.setProperty(PROP_IGNORE_USER_CONFIGURATION, Boolean.TRUE.toString());
					return sharedConfig.toURL();
				}
			}
		} catch (MalformedURLException e) {
			return null;
		}

		//Last resort
		try {
			return Utils.buildURL(specifiedURL);
		} catch (MalformedURLException e) {
			//Ignore
		}

		return null;
	}

	private long getLastKnownBundlesInfoBaseTimestamp(File configFolder) {
		File storedSharedTimestamp = new File(configFolder, BASE_TIMESTAMP_FILE_BUNDLESINFO);
		if (!storedSharedTimestamp.exists())
			return NO_TIMESTAMP;

		Properties p = new Properties();
		InputStream is = null;
		try {
			try {
				is = new BufferedInputStream(new FileInputStream(storedSharedTimestamp));
				p.load(is);
				if (p.get(KEY_BUNDLESINFO_TIMESTAMP) != null) {
					return Long.valueOf((String) p.get(KEY_BUNDLESINFO_TIMESTAMP)).longValue();
				}
			} finally {
				is.close();
			}
		} catch (IOException e) {
			return NO_TIMESTAMP;
		}
		return NO_TIMESTAMP;
	}

	private long getCurrentBundlesInfoBaseTimestamp(File sharedBundlesInfo) {
		if (!sharedBundlesInfo.exists())
			return NO_TIMESTAMP;
		return sharedBundlesInfo.lastModified();
	}

	public void applyConfiguration(URL url) throws IOException {
		synchronized (configurationLock) {
			if (Activator.DEBUG)
				System.out.println("applyConfiguration() URL=" + url);
			if (url == null)
				return;
			configurationURL = url;

			if (this.configApplier == null)
				configApplier = new ConfigApplier(context, bundle);
			configApplier.install(url, isExclusiveInstallation());
		}
	}

	private boolean isExclusiveInstallation() {
		String value = context.getProperty(SimpleConfiguratorConstants.PROP_KEY_EXCLUSIVE_INSTALLATION);
		if (value == null || value.trim().length() == 0)
			value = "true";
		return Boolean.valueOf(value).booleanValue();
	}

	public void applyConfiguration() throws IOException {
		synchronized (configurationLock) {
			configurationURL = getConfigurationURL();
			applyConfiguration(configurationURL);
		}
	}

	public URL getUrlInUse() {
		synchronized (configurationLock) {
			return configurationURL;
		}
	}
}
