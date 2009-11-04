/*******************************************************************************
 * Copyright (c) 2007, 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Cloudsmith - https://bugs.eclipse.org/bugs/show_bug.cgi?id=226401
 *     EclipseSource - ongoing development
 *******************************************************************************/
package org.eclipse.equinox.internal.p2.director.app;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import org.eclipse.core.runtime.*;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.equinox.internal.p2.core.helpers.*;
import org.eclipse.equinox.internal.provisional.p2.artifact.repository.IArtifactRepositoryManager;
import org.eclipse.equinox.internal.provisional.p2.core.ProvisionException;
import org.eclipse.equinox.internal.provisional.p2.director.*;
import org.eclipse.equinox.internal.provisional.p2.engine.*;
import org.eclipse.equinox.internal.provisional.p2.metadata.*;
import org.eclipse.equinox.internal.provisional.p2.metadata.Version;
import org.eclipse.equinox.internal.provisional.p2.metadata.query.*;
import org.eclipse.equinox.internal.provisional.p2.metadata.repository.IMetadataRepositoryManager;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.IProvisioningAgentProvider;
import org.eclipse.equinox.p2.metadata.query.IQuery;
import org.eclipse.osgi.framework.log.FrameworkLog;
import org.eclipse.osgi.service.environment.EnvironmentInfo;
import org.eclipse.osgi.util.NLS;
import org.osgi.framework.*;
import org.osgi.service.packageadmin.PackageAdmin;

/**
 * This director implementation is a complete replacement for the old director application
 * found in {@link Application}. This implementation has simplified command line 
 * arguments, and doesn't require the user to set system property such as the
 * p2 data location. See bug 268138 for related discussion.
 */
public class DirectorApplication implements IApplication {
	class LocationQueryable implements IQueryable {
		private URI location;

		public LocationQueryable(URI location) {
			this.location = location;
			Assert.isNotNull(location);
		}

		public Collector query(IQuery query, Collector collector, IProgressMonitor monitor) {
			return getInstallableUnits(location, query, collector, monitor);
		}
	}

	private static class CommandLineOption {
		private final String[] identifiers;
		private final String optionSyntaxString;
		private final String helpString;

		CommandLineOption(String[] identifiers, String optionSyntaxString, String helpString) {
			this.identifiers = identifiers;
			this.optionSyntaxString = optionSyntaxString;
			this.helpString = helpString;
		}

		boolean isOption(String opt) {
			int idx = identifiers.length;
			while (--idx >= 0)
				if (identifiers[idx].equalsIgnoreCase(opt))
					return true;
			return false;
		}

		void appendHelp(PrintStream out) {
			out.print(identifiers[0]);
			for (int idx = 1; idx < identifiers.length; ++idx) {
				out.print(" | "); //$NON-NLS-1$
				out.print(identifiers[idx]);
			}
			if (optionSyntaxString != null) {
				out.print(' ');
				out.print(optionSyntaxString);
			}
			out.println();
			out.print("  "); //$NON-NLS-1$
			out.println(helpString);
		}
	}

	private static final CommandLineOption OPTION_HELP = new CommandLineOption(new String[] {"-help", "-h", "-?"}, null, Messages.Help_Prints_this_command_line_help); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	private static final CommandLineOption OPTION_LIST = new CommandLineOption(new String[] {"-list", "-l"}, Messages.Help_lb_lt_comma_separated_list_gt_rb, Messages.Help_List_all_IUs_found_in_repos); //$NON-NLS-1$ //$NON-NLS-2$
	private static final CommandLineOption OPTION_INSTALL_IU = new CommandLineOption(new String[] {"-installIU", "-installIUs", "-i"}, Messages.Help_lt_comma_separated_list_gt, Messages.Help_Installs_the_listed_IUs); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	private static final CommandLineOption OPTION_UNINSTALL_IU = new CommandLineOption(new String[] {"-uninstallIU", "-uninstallIUs", "-u"}, Messages.Help_lt_comma_separated_list_gt, Messages.Help_Uninstalls_the_listed_IUs); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	private static final CommandLineOption OPTION_REVERT = new CommandLineOption(new String[] {"-revert"}, Messages.Help_lt_comma_separated_list_gt, Messages.Help_Revert_to_previous_state); //$NON-NLS-1$
	private static final CommandLineOption OPTION_DESTINATION = new CommandLineOption(new String[] {"-destination", "-d"}, Messages.Help_lt_path_gt, Messages.Help_The_folder_in_which_the_targetd_product_is_located); //$NON-NLS-1$ //$NON-NLS-2$
	private static final CommandLineOption OPTION_METADATAREPOS = new CommandLineOption(new String[] {"-metadatarepository", "metadatarepositories", "-m"}, Messages.Help_lt_comma_separated_list_gt, Messages.Help_A_list_of_URLs_denoting_metadata_repositories); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	private static final CommandLineOption OPTION_ARTIFACTREPOS = new CommandLineOption(new String[] {"-artifactrepository", "artifactrepositories", "-a"}, Messages.Help_lt_comma_separated_list_gt, Messages.Help_A_list_of_URLs_denoting_artifact_repositories); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	private static final CommandLineOption OPTION_REPOSITORIES = new CommandLineOption(new String[] {"-repository", "repositories", "-r"}, Messages.Help_lt_comma_separated_list_gt, Messages.Help_A_list_of_URLs_denoting_colocated_repositories); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	private static final CommandLineOption OPTION_VERIFY_ONLY = new CommandLineOption(new String[] {"-verifyOnly"}, null, Messages.Help_Only_verify_dont_install); //$NON-NLS-1$
	private static final CommandLineOption OPTION_PROFILE = new CommandLineOption(new String[] {"-profile", "-p"}, Messages.Help_lt_name_gt, Messages.Help_Defines_what_profile_to_use_for_the_actions); //$NON-NLS-1$ //$NON-NLS-2$
	private static final CommandLineOption OPTION_FLAVOR = new CommandLineOption(new String[] {"-flavor", "-f"}, Messages.Help_lt_name_gt, Messages.Help_Defines_flavor_to_use_for_created_profile); //$NON-NLS-1$ //$NON-NLS-2$
	private static final CommandLineOption OPTION_SHARED = new CommandLineOption(new String[] {"-shared", "-s"}, Messages.Help_lb_lt_path_gt_rb, Messages.Help_Use_a_shared_location_for_the_install); //$NON-NLS-1$ //$NON-NLS-2$
	private static final CommandLineOption OPTION_BUNDLEPOOL = new CommandLineOption(new String[] {"-bundlepool", "-b"}, Messages.Help_lt_path_gt, Messages.Help_The_location_where_the_plugins_and_features_will_be_stored); //$NON-NLS-1$ //$NON-NLS-2$
	private static final CommandLineOption OPTION_PROFILE_PROPS = new CommandLineOption(new String[] {"-profileproperties"}, Messages.Help_lt_comma_separated_list_gt, Messages.Help_A_list_of_properties_in_the_form_key_value_pairs); //$NON-NLS-1$
	private static final CommandLineOption OPTION_ROAMING = new CommandLineOption(new String[] {"-roaming"}, null, Messages.Help_Indicates_that_the_product_can_be_moved); //$NON-NLS-1$
	private static final CommandLineOption OPTION_P2_OS = new CommandLineOption(new String[] {"-p2.os"}, null, Messages.Help_The_OS_when_profile_is_created); //$NON-NLS-1$
	private static final CommandLineOption OPTION_P2_WS = new CommandLineOption(new String[] {"-p2.ws"}, null, Messages.Help_The_WS_when_profile_is_created); //$NON-NLS-1$
	private static final CommandLineOption OPTION_P2_ARCH = new CommandLineOption(new String[] {"-p2.arch"}, null, Messages.Help_The_ARCH_when_profile_is_created); //$NON-NLS-1$
	private static final CommandLineOption OPTION_P2_NL = new CommandLineOption(new String[] {"-p2.nl"}, null, Messages.Help_The_NL_when_profile_is_created); //$NON-NLS-1$

	private static final Integer EXIT_ERROR = new Integer(13);
	static private final String FLAVOR_DEFAULT = "tooling"; //$NON-NLS-1$
	static private final String PROP_P2_PROFILE = "eclipse.p2.profile"; //$NON-NLS-1$

	public static final String LINE_SEPARATOR = System.getProperty("line.separator"); //$NON-NLS-1$

	private static void getURIs(List uris, String spec) throws CoreException {
		if (spec == null)
			return;
		String[] urlSpecs = StringHelper.getArrayFromString(spec, ',');
		for (int i = 0; i < urlSpecs.length; i++) {
			try {
				uris.add(URIUtil.fromString(urlSpecs[i]));
			} catch (URISyntaxException e) {
				throw new ProvisionException(NLS.bind(Messages.unable_to_parse_0_to_uri_1, urlSpecs[i], e.getMessage()));
			}
		}
	}

	private static String getRequiredArgument(String[] args, int argIdx) throws CoreException {
		if (argIdx < args.length) {
			String arg = args[argIdx];
			if (!arg.startsWith("-")) //$NON-NLS-1$
				return arg;
		}
		throw new ProvisionException(NLS.bind(Messages.option_0_requires_an_argument, args[argIdx - 1]));
	}

	private static String getOptionalArgument(String[] args, int argIdx) {
		//Look ahead to the next argument
		++argIdx;
		if (argIdx < args.length) {
			String arg = args[argIdx];
			if (!arg.startsWith("-")) //$NON-NLS-1$
				return arg;
		}
		return null;
	}

	private static void parseIUsArgument(List vnames, String arg) {
		String[] roots = StringHelper.getArrayFromString(arg, ',');
		for (int i = 0; i < roots.length; ++i)
			vnames.add(VersionedId.parse(roots[i]));
	}

	private static File processFileArgument(String arg) {
		if (arg.startsWith("file:")) //$NON-NLS-1$
			arg = arg.substring(5);

		// we create a path object here to handle ../ entries in the middle of paths
		return Path.fromOSString(arg).toFile();
	}

	private IArtifactRepositoryManager artifactManager;
	IMetadataRepositoryManager metadataManager;

	private URI[] artifactReposForRemoval;
	private URI[] metadataReposForRemoval;

	private final List artifactRepositoryLocations = new ArrayList();
	private final List metadataRepositoryLocations = new ArrayList();
	private final List rootsToInstall = new ArrayList();
	private final List rootsToUninstall = new ArrayList();
	private final List rootsToList = new ArrayList();

	private File bundlePool = null;
	private File destination;
	private File sharedLocation;
	private String flavor;
	private boolean printHelpInfo = false;
	private boolean printIUList = false;
	private long revertToPreviousState = -1;
	private boolean verifyOnly = false;
	private boolean roamingProfile = false;
	private boolean stackTrace = false;
	private String profileId;
	private String profileProperties; // a comma-separated list of property pairs "tag=value"
	private String ws;
	private String os;
	private String arch;
	private String nl;

	private IEngine engine;
	private boolean noProfileId = false;
	private PackageAdmin packageAdmin;
	private ServiceReference packageAdminRef;
	private ServiceReference agentProviderRef;
	private IPlanner planner;

	private IProvisioningAgent agent;

	private ProfileChangeRequest buildProvisioningRequest(IProfile profile, IInstallableUnit[] installs, IInstallableUnit[] uninstalls) {
		ProfileChangeRequest request = new ProfileChangeRequest(profile);
		markRoots(request, installs);
		markRoots(request, uninstalls);
		request.addInstallableUnits(installs);
		request.removeInstallableUnits(uninstalls);
		return request;
	}

	private void cleanupRepositories() {
		if (artifactReposForRemoval != null && artifactManager != null) {
			for (int i = 0; i < artifactReposForRemoval.length && artifactReposForRemoval[i] != null; i++) {
				artifactManager.removeRepository(artifactReposForRemoval[i]);
			}
		}
		if (metadataReposForRemoval != null && metadataManager != null) {
			for (int i = 0; i < metadataReposForRemoval.length && metadataReposForRemoval[i] != null; i++) {
				metadataManager.removeRepository(metadataReposForRemoval[i]);
			}
		}
	}

	private Collector collectRootIUs(IQuery query, Collector collector) {
		IProgressMonitor nullMonitor = new NullProgressMonitor();

		int top = metadataRepositoryLocations.size();
		if (top == 0)
			return getInstallableUnits(null, query, collector, nullMonitor);

		Collector result = collector != null ? collector : new Collector();
		IQueryable[] locationQueryables = new IQueryable[top];
		for (int i = 0; i < top; i++)
			locationQueryables[i] = new LocationQueryable((URI) metadataRepositoryLocations.get(i));
		return new CompoundQueryable(locationQueryables).query(query, result, nullMonitor);
	}

	private IInstallableUnit[] collectRoots(IProfile profile, List rootNames, boolean forInstall) throws CoreException {
		ArrayList allRoots = new ArrayList();
		int top = rootNames.size();
		for (int i = 0; i < top; ++i) {
			IVersionedId rootName = (IVersionedId) rootNames.get(i);
			Version v = rootName.getVersion();
			IQuery query = new InstallableUnitQuery(rootName.getId(), Version.emptyVersion.equals(v) ? VersionRange.emptyRange : new VersionRange(v, true, v, true));
			Collector roots;
			if (forInstall)
				roots = collectRootIUs(new PipedQuery(new IQuery[] {query, new LatestIUVersionQuery()}), new Collector());
			else
				roots = new Collector();
			if (roots.size() <= 0)
				roots = profile.query(query, roots, new NullProgressMonitor());
			if (roots.size() <= 0)
				throw new CoreException(new Status(IStatus.ERROR, org.eclipse.equinox.internal.p2.director.app.Activator.ID, NLS.bind(Messages.Missing_IU, rootName)));
			allRoots.addAll(roots.toCollection());
		}
		return (IInstallableUnit[]) allRoots.toArray(new IInstallableUnit[allRoots.size()]);

	}

	synchronized Bundle getBundle(String symbolicName) {
		if (packageAdmin == null)
			return null;

		Bundle[] bundles = packageAdmin.getBundles(symbolicName, null);
		if (bundles == null)
			return null;
		//Return the first bundle that is not installed or uninstalled
		for (int i = 0; i < bundles.length; i++) {
			if ((bundles[i].getState() & (Bundle.INSTALLED | Bundle.UNINSTALLED)) == 0) {
				return bundles[i];
			}
		}
		return null;
	}

	private String getEnvironmentProperty() {
		HashMap values = new HashMap();
		if (os != null)
			values.put("osgi.os", os); //$NON-NLS-1$
		if (nl != null)
			values.put("osgi.nl", nl); //$NON-NLS-1$
		if (ws != null)
			values.put("osgi.ws", ws); //$NON-NLS-1$
		if (arch != null)
			values.put("osgi.arch", arch); //$NON-NLS-1$
		return values.isEmpty() ? null : toString(values);
	}

	private IProfile initializeProfile() throws CoreException {
		IProfileRegistry profileRegistry = (IProfileRegistry) agent.getService(IProfileRegistry.class.getName());
		if (profileId == null) {
			profileId = IProfileRegistry.SELF;
			noProfileId = true;
		}
		IProfile profile = profileRegistry.getProfile(profileId);
		if (profile == null) {
			if (destination == null)
				missingArgument("destination"); //$NON-NLS-1$
			if (flavor == null)
				flavor = System.getProperty("eclipse.p2.configurationFlavor", FLAVOR_DEFAULT); //$NON-NLS-1$

			Properties props = new Properties();
			props.setProperty(IProfile.PROP_INSTALL_FOLDER, destination.toString());
			if (bundlePool == null)
				props.setProperty(IProfile.PROP_CACHE, sharedLocation == null ? destination.getAbsolutePath() : sharedLocation.getAbsolutePath());
			else
				props.setProperty(IProfile.PROP_CACHE, bundlePool.getAbsolutePath());
			if (roamingProfile)
				props.setProperty(IProfile.PROP_ROAMING, Boolean.TRUE.toString());

			String env = getEnvironmentProperty();
			if (env != null)
				props.setProperty(IProfile.PROP_ENVIRONMENTS, env);
			if (profileProperties != null)
				putProperties(profileProperties, props);
			profile = profileRegistry.addProfile(profileId, props);
		}
		return profile;
	}

	private void initializeRepositories() throws CoreException {
		if (rootsToInstall.isEmpty() && revertToPreviousState == -1)
			// Not much point initializing repositories if we have nothing to install
			return;

		if (artifactRepositoryLocations == null)
			missingArgument("-artifactRepository"); //$NON-NLS-1$

		artifactManager = (IArtifactRepositoryManager) agent.getService(IArtifactRepositoryManager.SERVICE_NAME);
		if (artifactManager == null)
			throw new ProvisionException(Messages.Application_NoManager);

		int removalIdx = 0;
		boolean anyValid = false; // do we have any valid repos or did they all fail to load?
		artifactReposForRemoval = new URI[artifactRepositoryLocations.size()];
		for (int i = 0; i < artifactRepositoryLocations.size(); i++) {
			URI location = (URI) artifactRepositoryLocations.get(i);
			try {
				if (!artifactManager.contains(location)) {
					artifactManager.loadRepository(location, null);
					artifactReposForRemoval[removalIdx++] = location;
				}
				anyValid = true;
			} catch (ProvisionException e) {
				//one of the repositories did not load
				LogHelper.log(e.getStatus());
			}
		}
		if (!anyValid)
			//all repositories failed to load
			throw new ProvisionException(Messages.Application_NoRepositories);

		if (metadataRepositoryLocations == null)
			missingArgument("metadataRepository"); //$NON-NLS-1$

		metadataManager = (IMetadataRepositoryManager) agent.getService(IMetadataRepositoryManager.SERVICE_NAME);
		if (metadataManager == null)
			throw new ProvisionException(Messages.Application_NoManager);

		removalIdx = 0;
		anyValid = false; // do we have any valid repos or did they all fail to load?
		int top = metadataRepositoryLocations.size();
		metadataReposForRemoval = new URI[top];
		for (int i = 0; i < top; i++) {
			URI location = (URI) metadataRepositoryLocations.get(i);
			try {
				if (!metadataManager.contains(location)) {
					metadataManager.loadRepository(location, null);
					metadataReposForRemoval[removalIdx++] = location;
				}
				anyValid = true;
			} catch (ProvisionException e) {
				//one of the repositories did not load
				LogHelper.log(e.getStatus());
			}
		}
		if (!anyValid)
			//all repositories failed to load
			throw new ProvisionException(Messages.Application_NoRepositories);
	}

	private void initializeServices() throws CoreException {
		BundleContext context = Activator.getContext();
		packageAdminRef = context.getServiceReference(PackageAdmin.class.getName());
		packageAdmin = (PackageAdmin) context.getService(packageAdminRef);
		agentProviderRef = context.getServiceReference(IProvisioningAgentProvider.SERVICE_NAME);
		IProvisioningAgentProvider provider = (IProvisioningAgentProvider) context.getService(agentProviderRef);
		URI p2DataArea;
		if (destination != null || sharedLocation != null) {
			File dataAreaFile = sharedLocation == null ? new File(destination, "p2") : sharedLocation;//$NON-NLS-1$
			p2DataArea = dataAreaFile.toURI();
		} else {
			p2DataArea = null;
		}
		agent = provider.createAgent(p2DataArea);
		if (profileId == null) {
			if (destination != null) {
				File configIni = new File(destination, "configuration/config.ini"); //$NON-NLS-1$
				InputStream in = null;
				try {
					Properties ciProps = new Properties();
					in = new BufferedInputStream(new FileInputStream(configIni));
					ciProps.load(in);
					profileId = ciProps.getProperty(PROP_P2_PROFILE);
				} catch (IOException e) {
					// Ignore
				} finally {
					if (in != null)
						try {
							in.close();
						} catch (IOException e) {
							// Ignore;
						}
				}
				if (profileId == null)
					profileId = destination.toString();
			}
		}
		if (profileId != null)
			agent.registerService(PROP_P2_PROFILE, profileId);
		else
			agent.unregisterService(PROP_P2_PROFILE, null);

		IDirector director = (IDirector) agent.getService(IDirector.SERVICE_NAME);
		if (director == null)
			throw new ProvisionException(Messages.Missing_director);

		planner = (IPlanner) agent.getService(IPlanner.SERVICE_NAME);
		if (planner == null)
			throw new ProvisionException(Messages.Missing_planner);

		engine = (IEngine) agent.getService(IEngine.SERVICE_NAME);
		if (engine == null)
			throw new ProvisionException(Messages.Missing_Engine);
	}

	private void logFailure(IStatus status) {
		FrameworkLog log = (FrameworkLog) ServiceHelper.getService(Activator.getContext(), FrameworkLog.class.getName());
		if (log != null)
			System.err.println("Application failed, log file location: " + log.getFile()); //$NON-NLS-1$
		LogHelper.log(status);
	}

	private void markRoots(ProfileChangeRequest request, IInstallableUnit[] roots) {
		for (int idx = 0; idx < roots.length; ++idx)
			request.setInstallableUnitProfileProperty(roots[idx], IProfile.PROP_PROFILE_ROOT_IU, Boolean.TRUE.toString());
	}

	private void missingArgument(String argumentName) throws CoreException {
		throw new ProvisionException(NLS.bind(Messages.Missing_Required_Argument, argumentName));
	}

	private void performList() throws CoreException {
		if (metadataRepositoryLocations.isEmpty())
			missingArgument("metadataRepository"); //$NON-NLS-1$

		ArrayList allRoots = new ArrayList();
		if (rootsToList.size() == 0) {
			Collector roots = collectRootIUs(InstallableUnitQuery.ANY, null);
			allRoots.addAll(roots.toCollection());
		} else {
			Iterator r = rootsToList.iterator();
			while (r.hasNext()) {
				IVersionedId rootName = (IVersionedId) r.next();
				Version v = rootName.getVersion();
				IQuery query = new InstallableUnitQuery(rootName.getId(), Version.emptyVersion.equals(v) ? VersionRange.emptyRange : new VersionRange(v, true, v, true));
				Collector roots = collectRootIUs(query, null);
				allRoots.addAll(roots.toCollection());
			}
		}

		Collections.sort(allRoots);
		Iterator i = allRoots.iterator();
		while (i.hasNext()) {
			IInstallableUnit iu = (IInstallableUnit) i.next();
			System.out.println(iu.getId() + '=' + iu.getVersion());
		}
	}

	private void performProvisioningActions() throws CoreException {
		IProfile profile = initializeProfile();
		IInstallableUnit[] installs = collectRoots(profile, rootsToInstall, true);
		IInstallableUnit[] uninstalls = collectRoots(profile, rootsToUninstall, false);

		// keep this result status in case there is a problem so we can report it to the user
		boolean wasRoaming = Boolean.valueOf(profile.getProperty(IProfile.PROP_ROAMING)).booleanValue();
		try {
			updateRoamingProperties(profile);
			ProvisioningContext context = new ProvisioningContext((URI[]) metadataRepositoryLocations.toArray(new URI[metadataRepositoryLocations.size()]));
			context.setArtifactRepositories((URI[]) artifactRepositoryLocations.toArray(new URI[artifactRepositoryLocations.size()]));
			ProfileChangeRequest request = buildProvisioningRequest(profile, installs, uninstalls);
			printRequest(request);
			planAndExecute(profile, context, request);
		} finally {
			// if we were originally were set to be roaming and we changed it, change it back before we return
			if (wasRoaming && !Boolean.valueOf(profile.getProperty(IProfile.PROP_ROAMING)).booleanValue())
				setRoaming(profile);
		}
	}

	private void planAndExecute(IProfile profile, ProvisioningContext context, ProfileChangeRequest request) throws CoreException {
		ProvisioningPlan result = planner.getProvisioningPlan(request, context, new NullProgressMonitor());
		IStatus operationStatus = result.getStatus();
		if (!operationStatus.isOK())
			throw new CoreException(operationStatus);
		executePlan(context, result);
	}

	private void executePlan(ProvisioningContext context, ProvisioningPlan result) throws CoreException {
		IStatus operationStatus;
		if (!verifyOnly) {
			operationStatus = PlanExecutionHelper.executePlan(result, engine, context, new NullProgressMonitor());
			if (!operationStatus.isOK())
				throw new CoreException(operationStatus);
		}
	}

	private void printRequest(ProfileChangeRequest request) {
		IInstallableUnit[] toAdd = request.getAddedInstallableUnits();
		for (int i = 0; i < toAdd.length; i++) {
			System.out.println(NLS.bind(Messages.Installing, toAdd[i].getId(), toAdd[i].getVersion()));
		}
		IInstallableUnit[] toRemove = request.getRemovedInstallableUnits();
		for (int i = 0; i < toRemove.length; i++) {
			System.out.println(NLS.bind(Messages.Uninstalling, toRemove[i].getId(), toRemove[i].getVersion()));
		}
	}

	public void processArguments(String[] args) throws CoreException {
		if (args == null) {
			printHelpInfo = true;
			return;
		}

		for (int i = 0; i < args.length; i++) {
			// check for args without parameters (i.e., a flag arg)
			String opt = args[i];
			if (OPTION_LIST.isOption(opt)) {
				printIUList = true;
				String optionalArgument = getOptionalArgument(args, i);
				if (optionalArgument != null) {
					parseIUsArgument(rootsToList, optionalArgument);
					i++;
				}
				continue;
			}

			if (OPTION_HELP.isOption(opt)) {
				printHelpInfo = true;
				continue;
			}

			if (OPTION_INSTALL_IU.isOption(opt)) {
				parseIUsArgument(rootsToInstall, getRequiredArgument(args, ++i));
				continue;
			}

			if (OPTION_UNINSTALL_IU.isOption(opt)) {
				parseIUsArgument(rootsToUninstall, getRequiredArgument(args, ++i));
				continue;
			}

			if (OPTION_REVERT.isOption(opt)) {
				String targettedState = getOptionalArgument(args, i);
				if (targettedState == null) {
					revertToPreviousState = 0;
				} else {
					i++;
					revertToPreviousState = Long.valueOf(targettedState).longValue();
				}
				continue;

			}
			if (OPTION_PROFILE.isOption(opt)) {
				profileId = getRequiredArgument(args, ++i);
				continue;
			}

			if (OPTION_FLAVOR.isOption(opt)) {
				flavor = getRequiredArgument(args, ++i);
				continue;
			}

			if (OPTION_SHARED.isOption(opt)) {
				if (++i < args.length) {
					String nxt = args[i];
					if (nxt.startsWith("-")) //$NON-NLS-1$
						--i; // Oops, that's the next option, not an argument
					else
						sharedLocation = processFileArgument(nxt);
				}
				if (sharedLocation == null)
					// -shared without an argument means "Use default shared area"
					sharedLocation = Path.fromOSString(System.getProperty("user.home")).append(".p2/").toFile(); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}

			if (OPTION_DESTINATION.isOption(opt)) {
				destination = processFileArgument(getRequiredArgument(args, ++i));
				continue;
			}

			if (OPTION_BUNDLEPOOL.isOption(opt)) {
				bundlePool = processFileArgument(getRequiredArgument(args, ++i));
				continue;
			}

			if (OPTION_METADATAREPOS.isOption(opt)) {
				getURIs(metadataRepositoryLocations, getRequiredArgument(args, ++i));
				continue;
			}

			if (OPTION_ARTIFACTREPOS.isOption(opt)) {
				getURIs(artifactRepositoryLocations, getRequiredArgument(args, ++i));
				continue;
			}

			if (OPTION_REPOSITORIES.isOption(opt)) {
				String arg = getRequiredArgument(args, ++i);
				getURIs(metadataRepositoryLocations, arg);
				getURIs(artifactRepositoryLocations, arg);
				continue;
			}

			if (OPTION_PROFILE_PROPS.isOption(opt)) {
				profileProperties = getRequiredArgument(args, ++i);
				continue;
			}

			if (OPTION_ROAMING.isOption(opt)) {
				roamingProfile = true;
				continue;
			}

			if (OPTION_VERIFY_ONLY.isOption(opt)) {
				verifyOnly = true;
				continue;
			}

			if (OPTION_P2_OS.isOption(opt)) {
				os = getRequiredArgument(args, ++i);
				continue;
			}

			if (OPTION_P2_WS.isOption(opt)) {
				ws = getRequiredArgument(args, ++i);
				continue;
			}

			if (OPTION_P2_NL.isOption(opt)) {
				nl = getRequiredArgument(args, ++i);
				continue;
			}

			if (OPTION_P2_ARCH.isOption(opt)) {
				arch = getRequiredArgument(args, ++i);
				continue;
			}
			throw new ProvisionException(NLS.bind(Messages.unknown_option_0, opt));
		}

		if (!printHelpInfo && !printIUList && rootsToInstall.isEmpty() && rootsToUninstall.isEmpty() && revertToPreviousState == -1) {
			System.out.println(Messages.Help_Missing_argument);
			printHelpInfo = true;
		}
	}

	/**
	 * @param pairs	a comma separated list of tag=value pairs
	 * @param properties the collection into which the pairs are put
	 */
	private void putProperties(String pairs, Properties properties) {
		String[] propPairs = StringHelper.getArrayFromString(pairs, ',');
		for (int i = 0; i < propPairs.length; ++i) {
			String propPair = propPairs[i];
			int eqIdx = propPair.indexOf('=');
			if (eqIdx < 0)
				continue;
			String tag = propPair.substring(0, eqIdx).trim();
			if (tag.length() == 0)
				continue;
			String value = propPair.substring(eqIdx + 1).trim();
			if (value.length() > 0)
				properties.put(tag, value);
		}
	}

	private void cleanupServices() {
		BundleContext context = Activator.getContext();
		//dispose agent
		if (agentProviderRef != null)
			context.ungetService(agentProviderRef);
		if (packageAdminRef != null)
			context.ungetService(packageAdminRef);
	}

	public Object run(String[] args) throws CoreException {
		long time = System.currentTimeMillis();

		try {
			processArguments(args);
			if (printHelpInfo)
				performHelpInfo();
			else {
				initializeServices();
				initializeRepositories();
				if (revertToPreviousState >= 0) {
					revertToPreviousState();
				} else if (!(rootsToInstall.isEmpty() && rootsToUninstall.isEmpty()))
					performProvisioningActions();
				if (printIUList)
					performList();
				System.out.println(NLS.bind(Messages.Operation_complete, new Long(System.currentTimeMillis() - time)));
			}
			return IApplication.EXIT_OK;
		} catch (CoreException e) {
			deeplyPrint(e.getStatus(), System.err, 0);
			logFailure(e.getStatus());
			//set empty exit data to suppress error dialog from launcher
			setSystemProperty("eclipse.exitdata", ""); //$NON-NLS-1$ //$NON-NLS-2$
			return EXIT_ERROR;
		} finally {
			if (packageAdminRef != null) {
				cleanupRepositories();
				cleanupServices();
			}
		}
	}

	private void revertToPreviousState() throws CoreException {
		IProfile profile = initializeProfile();
		IProfileRegistry profileRegistry = (IProfileRegistry) agent.getService(IProfileRegistry.class.getName());
		IProfile targetProfile = null;
		if (revertToPreviousState == 0) {
			long[] profiles = profileRegistry.listProfileTimestamps(profile.getProfileId());
			if (profiles.length == 0)
				return;
			targetProfile = profileRegistry.getProfile(profile.getProfileId(), profiles[profiles.length - 1]);
		} else {
			targetProfile = profileRegistry.getProfile(profile.getProfileId(), revertToPreviousState);
		}
		if (targetProfile == null)
			throw new CoreException(new Status(IStatus.ERROR, Activator.ID, Messages.Missing_profile));
		ProvisioningPlan plan = planner.getDiffPlan(profile, targetProfile, new NullProgressMonitor());

		ProvisioningContext context = new ProvisioningContext((URI[]) metadataRepositoryLocations.toArray(new URI[metadataRepositoryLocations.size()]));
		context.setArtifactRepositories((URI[]) artifactRepositoryLocations.toArray(new URI[artifactRepositoryLocations.size()]));
		executePlan(context, plan);
	}

	/**
	 * Sets a system property, using the EnvironmentInfo service if possible.
	 */
	private void setSystemProperty(String key, String value) {
		EnvironmentInfo env = (EnvironmentInfo) ServiceHelper.getService(Activator.getContext(), EnvironmentInfo.class.getName());
		if (env != null) {
			env.setProperty(key, value);
		} else {
			System.getProperties().put(key, value);
		}
	}

	private static void appendLevelPrefix(PrintStream strm, int level) {
		for (int idx = 0; idx < level; ++idx)
			strm.print(' ');
	}

	Collector getInstallableUnits(URI location, IQuery query, Collector collector, IProgressMonitor monitor) {
		IQueryable queryable = null;
		if (location == null) {
			queryable = metadataManager;
		} else {
			try {
				queryable = metadataManager.loadRepository(location, monitor);
			} catch (ProvisionException e) {
				//repository is not available - just return empty result
			}
		}
		if (queryable != null)
			return queryable.query(query, collector, monitor);
		return collector;
	}

	private void deeplyPrint(CoreException ce, PrintStream strm, int level) {
		appendLevelPrefix(strm, level);
		if (stackTrace)
			ce.printStackTrace(strm);
		deeplyPrint(ce.getStatus(), strm, level);
	}

	private void deeplyPrint(IStatus status, PrintStream strm, int level) {
		appendLevelPrefix(strm, level);
		String msg = status.getMessage();
		strm.println(msg);
		Throwable cause = status.getException();
		if (cause != null) {
			strm.print("Caused by: "); //$NON-NLS-1$
			if (stackTrace || !(msg.equals(cause.getMessage()) || msg.equals(cause.toString())))
				deeplyPrint(cause, strm, level);
		}

		if (status.isMultiStatus()) {
			IStatus[] children = status.getChildren();
			for (int i = 0; i < children.length; i++)
				deeplyPrint(children[i], strm, level + 1);
		}
	}

	private void deeplyPrint(Throwable t, PrintStream strm, int level) {
		if (t instanceof CoreException)
			deeplyPrint((CoreException) t, strm, level);
		else {
			appendLevelPrefix(strm, level);
			if (stackTrace)
				t.printStackTrace(strm);
			else {
				strm.println(t.toString());
				Throwable cause = t.getCause();
				if (cause != null) {
					strm.print("Caused by: "); //$NON-NLS-1$
					deeplyPrint(cause, strm, level);
				}
			}
		}
	}

	private void performHelpInfo() {
		CommandLineOption[] allOptions = new CommandLineOption[] {OPTION_HELP, OPTION_LIST, OPTION_INSTALL_IU, OPTION_UNINSTALL_IU, OPTION_REVERT, OPTION_DESTINATION, OPTION_METADATAREPOS, OPTION_ARTIFACTREPOS, OPTION_REPOSITORIES, OPTION_VERIFY_ONLY, OPTION_PROFILE, OPTION_FLAVOR, OPTION_SHARED, OPTION_BUNDLEPOOL, OPTION_PROFILE_PROPS, OPTION_ROAMING, OPTION_P2_OS, OPTION_P2_WS, OPTION_P2_ARCH, OPTION_P2_NL};
		for (int i = 0; i < allOptions.length; ++i) {
			allOptions[i].appendHelp(System.out);
		}
	}

	/*
	 * Set the roaming property on the given profile.
	 */
	private IStatus setRoaming(IProfile profile) {
		ProfileChangeRequest request = new ProfileChangeRequest(profile);
		request.setProfileProperty(IProfile.PROP_ROAMING, "true"); //$NON-NLS-1$
		ProvisioningContext context = new ProvisioningContext(new URI[0]);
		context.setArtifactRepositories(new URI[0]);
		ProvisioningPlan result = planner.getProvisioningPlan(request, context, new NullProgressMonitor());
		return PlanExecutionHelper.executePlan(result, engine, context, new NullProgressMonitor());
	}

	public Object start(IApplicationContext context) throws Exception {
		return run((String[]) context.getArguments().get("application.args")); //$NON-NLS-1$
	}

	private String toString(Map context) {
		StringBuffer result = new StringBuffer();
		Iterator entries = context.entrySet().iterator();
		while (entries.hasNext()) {
			Map.Entry entry = (Map.Entry) entries.next();
			if (result.length() > 0)
				result.append(',');
			result.append((String) entry.getKey());
			result.append('=');
			result.append((String) entry.getValue());
		}
		return result.toString();
	}

	private void updateRoamingProperties(IProfile profile) throws CoreException {
		// if the user didn't specify a destination path on the command-line
		// then we assume they are installing into the currently running
		// instance and we don't have anything to update
		if (destination == null)
			return;

		// if the user didn't set a profile id on the command-line this is ok if they
		// also didn't set the destination path. (handled in the case above) otherwise throw an error.
		if (noProfileId) // && destination != null
			throw new ProvisionException(Messages.Missing_profileid);

		// make sure that we are set to be roaming before we update the values
		if (!Boolean.valueOf(profile.getProperty(IProfile.PROP_ROAMING)).booleanValue())
			return;

		ProfileChangeRequest request = new ProfileChangeRequest(profile);
		if (!destination.equals(new File(profile.getProperty(IProfile.PROP_INSTALL_FOLDER))))
			request.setProfileProperty(IProfile.PROP_INSTALL_FOLDER, destination);
		if (!destination.equals(new File(profile.getProperty(IProfile.PROP_CACHE))))
			request.setProfileProperty(IProfile.PROP_CACHE, destination);
		if (request.getProfileProperties().size() == 0)
			return;

		// otherwise we have to make a change so set the profile to be non-roaming so the 
		// values don't get recalculated to the wrong thing if we are flushed from memory - we
		// will set it back later (see bug 269468)
		request.setProfileProperty(IProfile.PROP_ROAMING, "false"); //$NON-NLS-1$

		ProvisioningContext context = new ProvisioningContext(new URI[0]);
		context.setArtifactRepositories(new URI[0]);
		ProvisioningPlan result = planner.getProvisioningPlan(request, context, new NullProgressMonitor());
		IStatus status = PlanExecutionHelper.executePlan(result, engine, context, new NullProgressMonitor());
		if (!status.isOK())
			throw new CoreException(new MultiStatus(org.eclipse.equinox.internal.p2.director.app.Activator.ID, IStatus.ERROR, new IStatus[] {status}, NLS.bind(Messages.Cant_change_roaming, profile.getProfileId()), null));
	}

	public void stop() {
		// Nothing left to do here
	}
}