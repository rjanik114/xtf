package cz.xtf.manipulation;

import cz.xtf.TestConfiguration;
import cz.xtf.openshift.OpenShiftBinaryClient;
import cz.xtf.openshift.OpenShiftContext;
import cz.xtf.openshift.OpenShiftUtils;
import cz.xtf.openshift.OpenshiftUtil;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static cz.xtf.TestConfiguration.masterNamespace;
import static cz.xtf.TestConfiguration.masterPassword;
import static cz.xtf.TestConfiguration.masterUsername;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Project handler responsible for namespace preparation, project creation and cleanup.
 */
@Slf4j
public class ProjectHandler {

	private static final String TEMP_NAMESPACE_IDENTIFIER = "-automated";
	private static OpenshiftUtil openshift = OpenshiftUtil.getInstance();

	@Getter private String project;
	@Getter private String namespace;

	private boolean temporaryNamespace;
	private OpenShiftContext originalContext;

	private boolean used;

	/**
	 * Calls the {@link ProjectHandler#ProjectHandler(String, String)} with
	 * {@link TestConfiguration#masterNamespace()}.
	 */
	public ProjectHandler(String project) {
		this(project, masterNamespace());
	}

	/**
	 * Creates project handler.
	 *
	 * <p>
	 * In case the {@code} namespace is not provided (is blank), some temporary namespace will be created. This
	 * temporary namespace will be then removed in the cleanup.
	 * </p>
	 *
	 * @param project the name of the product to handle, cannot be null
	 * @param namespace the name of namespace (project) to use
	 */
	public ProjectHandler(@NonNull String project, String namespace) {
		this.project = project;
		if (isNotBlank(namespace)) {
			this.namespace = namespace;
		} else {
			this.namespace = project + TEMP_NAMESPACE_IDENTIFIER;
			temporaryNamespace = true;
		}
	}

	private static void createProject(String project) {
		log.info("action=create-project status=START project={} recreate=true", project);

		final OpenShiftContext originalContext = openshift.getContext();
		// create project under admin context
		openshift.setOpenShiftContext(OpenShiftContext.getContext(OpenShiftContext.ADMIN_CONTEXT_NAME));
		try {
			OpenShiftUtils.master().recreateProject(project);
		} catch (TimeoutException e) {
			log.warn("Failed to create {} project. Assuming it already exists.", project);
		}
		// restore original context
		openshift.setOpenShiftContext(originalContext);

		OpenShiftUtils.master().createORegSecret();

		log.info("action=create-project status=FINISH project={} recreate=true", project);
	}

	/**
	 * Prepares namespace if temporary and creates project.
	 */
	public void prepare() {
		if (used) {
			throw new IllegalStateException("Project handling was already used, create new handler!");
		}
		used = true;

		if (temporaryNamespace) {
			originalContext = openshift.getContext();
			log.info("action=create-temp-context status=START project={} namespace={}", project, namespace);
			OpenShiftContext.newContext(project, masterUsername(), masterPassword(), namespace);
			openshift.setOpenShiftContext(OpenShiftContext.getContext(project));
			log.info("action=create-temp-context status=FINISH project={} namespace={}", project, namespace);
		}
		createProject(namespace);
	}

	/**
	 * Records events.log to directory {@link OpenshiftUtil#getProjectLogsDir()}.
	 */
	public void saveEventsLog() {
		OpenshiftUtil.getProjectLogsDir().toFile().mkdirs();
		try (final FileWriter writer = new FileWriter(OpenshiftUtil.getProjectLogsDir().resolve("events.log").toFile())) {
			log.info("action=record-events status=START namespace={}", namespace);
			writer.write(OpenShiftBinaryClient.getInstance().executeCommandWithReturn(
					// error
					"Error executing 'oc get events -n " + namespace,
					// command
					"get",
					"events",
					"-n",
					namespace));
			log.info("action=record-events status=FINISH namespace={}", namespace);
		} catch (IOException e) {
			log.info("action=record-events status=ERROR namespace={}", namespace, e);
		}
	}

	/**
	 * Removes namespace if it is temporary.
	 */
	public void cleanup() {
		if (temporaryNamespace) {
			restoreContext();
			deleteNamespace();
		}
		used = true;
	}

	private void restoreContext() {
		OpenshiftUtil.getInstance().setOpenShiftContext(originalContext);
	}

	private void deleteNamespace() {
		log.info("action=remove-temp-namespace status=START namespace={}", namespace);
		openshift.deleteProject(namespace);
		log.info("action=remove-temp-namespace status=FINISH namespace={}", namespace);
	}
}
