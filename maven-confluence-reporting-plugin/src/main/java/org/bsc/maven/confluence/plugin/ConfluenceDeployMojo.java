package org.bsc.maven.confluence.plugin;

import biz.source_code.miniTemplator.MiniTemplator;
import biz.source_code.miniTemplator.MiniTemplator.VariableNotDefinedException;
import com.github.qwazer.mavenplugins.gitlog.CalculateRuleForSinceTagName;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactCollector;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.InvalidPluginDescriptorException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.report.projectinfo.AbstractProjectInfoRenderer;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.tools.plugin.DefaultPluginToolsRequest;
import org.apache.maven.tools.plugin.PluginToolsRequest;
import org.apache.maven.tools.plugin.extractor.ExtractionException;
import org.apache.maven.tools.plugin.generator.Generator;
import org.apache.maven.tools.plugin.generator.GeneratorUtils;
import org.apache.maven.tools.plugin.scanner.MojoScanner;
import org.bsc.maven.plugin.confluence.ConfluenceUtils;
import org.bsc.maven.reporting.model.Site;
import org.bsc.maven.reporting.renderer.CloudServiceChangelogRenderer;
import org.bsc.maven.reporting.renderer.DependenciesRenderer;
import org.bsc.maven.reporting.renderer.GitLogJiraIssuesRenderer;
import org.bsc.maven.reporting.renderer.ProjectSummaryRenderer;
import org.bsc.maven.reporting.renderer.ProjectTeamRenderer;
import org.bsc.maven.reporting.renderer.ScmRenderer;
import org.bsc.maven.reporting.renderer.ServiceDependencyHistoryRenderer;
import org.bsc.maven.reporting.sink.ConfluenceSink;
import org.codehaus.plexus.component.repository.ComponentDependency;
import org.codehaus.plexus.i18n.I18N;
import org.codehaus.swizzle.confluence.Confluence;
import org.codehaus.swizzle.confluence.Page;

import java.io.StringWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Generate Project's documentation in confluence's wiki format and deploy it
 */
@Mojo(name = "deploy", threadSafe = true)
public class ConfluenceDeployMojo extends AbstractConfluenceSiteMojo {

  private static final String POS_SERVICE_DEPENDENCIES = "pos.service.dependencies";
  private static final String POS_CLOUD_CHANGELOG = "pos.cloud.changelog";
  private static final String PROJECT_DEPENDENCIES_VAR = "project.dependencies";
  private static final String PROJECT_SCM_MANAGER_VAR = "project.scmManager";
  private static final String PROJECT_TEAM_VAR = "project.team";
  private static final String PROJECT_SUMMARY_VAR = "project.summary";
  private static final String GITLOG_JIRA_ISSUES_VAR = "gitlog.jiraIssues";
  private static final String GITLOG_SINCE_TAG_NAME = "gitlog.sinceTagName";

  /**
   * Local Repository.
   */
  @Parameter(defaultValue = "${localRepository}", required = true, readonly = true)
  protected ArtifactRepository localRepository;
  /**
   */
  @Component
  protected ArtifactMetadataSource artifactMetadataSource;
  /**
   */
  @Component
  private ArtifactCollector collector;
  /**
   *
   */
  @Component(role = org.apache.maven.artifact.factory.ArtifactFactory.class)
  protected ArtifactFactory factory;
  /**
   * Maven Project Builder.
   */
  @Component
  private MavenProjectBuilder mavenProjectBuilder;

  /**
   *
   */
  @Component
  protected I18N i18n;

  /**
   *
   */
  //@Parameter(property = "project.reporting.outputDirectory")
  @Parameter(property = "project.build.directory/generated-site/confluence", required = true)
  protected java.io.File outputDirectory;

  /**
   * Maven SCM Manager.
   */
  @Component(role = ScmManager.class)
  protected ScmManager scmManager;

  /**
   * The directory name to checkout right after the scm url
   */
  @Parameter(defaultValue = "${project.artifactId}", required = true)
  private String checkoutDirectoryName;
  /**
   * The scm anonymous connection url.
   */
  @Parameter(defaultValue = "${project.scm.connection}")
  private String anonymousConnection;
  /**
   * The scm developer connection url.
   */
  @Parameter(defaultValue = "${project.scm.developerConnection}")
  private String developerConnection;

  /**
   * The scm web access url.
   */
  @Parameter(defaultValue = "${project.scm.url}")
  private String webAccessUrl;

  /**
   * Set to true for enabling substitution of ${gitlog.jiraIssues} build-in variable
   *
   * @since 4.2
   */
  @Parameter(defaultValue = "false")
  private Boolean gitLogJiraIssuesEnable;

  @Parameter(defaultValue = "false")
  private Boolean posServiceDependencies;

  @Parameter
  private String gitPosServiceSinceTagName;

  /**
   * Parse git log commits since last occurrence of specified tag name
   *
   * @since 4.2
   */
  @Parameter
  private String gitLogSinceTagName;

  /**
   * Parse git log commits until first occurrence of specified tag name
   *
   * @since 4.2
   */
  @Parameter
  private String gitLogUntilTagName;

  /**
   * If specified, plugin will try to calculate and replace actual gitLogSinceTagName value
   * based on current project version ${project.version} and provided rule.
   * Possible values are
   * <ul>
   * <li>NO_RULE</li>
   * <li>CURRENT_MAJOR_VERSION</li>
   * <li>CURRENT_MINOR_VERSION</li>
   * <li>LATEST_RELEASE_VERSION</li>
   * </ul>
   *
   * @since 4.2
   */
  @Parameter(defaultValue = "NO_RULE")
  private CalculateRuleForSinceTagName gitLogCalculateRuleForSinceTagName;

  /**
   * Specify JIRA projects key to extract issues from gitlog
   * By default it will try extract all strings that match pattern (A-Za-z+)-\d+
   *
   * @since 4.2
   */
  @Parameter
  private List<String> gitLogJiraProjectKeyList;

  /**
   * The pattern to filter out tagName. Can be used for filter only version tags.
   *
   * @since 4.2
   */
  @Parameter
  private String gitLogTagNamesPattern;

  /**
   * Enable grouping by versions tag
   *
   * @since 4.2
   */
  @Parameter
  private Boolean gitLogGroupByVersions;

  @Parameter
  private Properties serviceDocumentationMap;

  /**
   *
   */
  public ConfluenceDeployMojo() {
    super();
  }

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    final Locale locale = Locale.getDefault();

    getLog().info(String.format("executeReport isSnapshot = [%b] isRemoveSnapshots = [%b]", isSnapshot(), isRemoveSnapshots()));

    loadUserInfoFromSettings();

    Site site = null;

    if (isSiteDescriptorValid()) {
      site = super.createFromModel();
    }

    if (site != null) {
      site.setBasedir(getSiteDescriptor());
      if (site.getHome().getName() != null) {
        setTitle(site.getHome().getName());
      } else {
        site.getHome().setName(getTitle());
      }

      java.util.List<String> _labels = getLabels();
      if (!_labels.isEmpty()) {
        site.getLabels().addAll(_labels);
      }
    } else

    {
      site = super.createFromFolder();

    }

    site.print(System.out);

    super.initTemplateProperties();

    if (project.getPackaging().

      equals("maven-plugin")

      )

    /////////////////////////////////////////////////////////////////
    // PLUGIN
    /////////////////////////////////////////////////////////////////
    {
      generatePluginReport(site, locale);
    } else

    /////////////////////////////////////////////////////////////////
    // PROJECT
    /////////////////////////////////////////////////////////////////
    {
      generateProjectReport(site, locale);
    }
  }

  private void generateProjectReport(final Site site, Locale locale) throws MojoExecutionException {
    final ReportingResolutionListener resolvedProjectListener = resolveProject();
    // Issue 32
    final String title = getTitle();
    //String title = project.getArtifactId() + "-" + project.getVersion();

    final MiniTemplator t;
    try {
      t = new MiniTemplator.Builder()
        .setSkipUndefinedVars(true)
        .build(Site.processUri(site.getHome().getUri()), getCharset());

    } catch (Exception e) {
      final String msg = "error loading template";
      getLog().error(msg, e);
      throw new MojoExecutionException(msg, e);
    }

    super.addStdProperties(t);

    /////////////////////////////////////////////////////////////////
    // SUMMARY
    /////////////////////////////////////////////////////////////////
    {

      final StringWriter w = new StringWriter(10*1024);
      final Sink sink = new ConfluenceSink(w);
      //final Sink sink = getSink();

      new ProjectSummaryRenderer(sink,
        project,
        i18n,
        locale).render();

      replaceMacroNameWithContent(t, w, PROJECT_SUMMARY_VAR);
    }

    /////////////////////////////////////////////////////////////////
    // TEAM
    /////////////////////////////////////////////////////////////////

    {

      final StringWriter w = new StringWriter(10*1024);
      final Sink sink = new ConfluenceSink(w);

      final AbstractProjectInfoRenderer renderer =
        new ProjectTeamRenderer(sink,
          project.getModel(),
          i18n,
          locale,
          getLog(),
          false /* showAvatarImages */
        );

      renderer.render();

      replaceMacroNameWithContent(t, w, PROJECT_TEAM_VAR);

    }

    /////////////////////////////////////////////////////////////////
    // SCM
    /////////////////////////////////////////////////////////////////

    {

      final StringWriter w = new StringWriter(10*1024);
      final Sink sink = new ConfluenceSink(w);
      //final Sink sink = getSink();
      final String scmTag = "";

      new ScmRenderer(getLog(),
        scmManager,
        sink,
        project.getModel(),
        i18n,
        locale,
        checkoutDirectoryName,
        webAccessUrl,
        anonymousConnection,
        developerConnection,
        scmTag).render();

      replaceMacroNameWithContent(t, w, PROJECT_SCM_MANAGER_VAR);
    }

    /////////////////////////////////////////////////////////////////
    // DEPENDENCIES
    /////////////////////////////////////////////////////////////////

    {
      final StringWriter w = new StringWriter(10*1024);
      final Sink sink = new ConfluenceSink(w);
      //final Sink sink = getSink();

      new DependenciesRenderer(sink,
        project,
        mavenProjectBuilder,
        localRepository,
        factory,
        i18n,
        locale,
        resolvedProjectListener,
        getLog()).render();

      replaceMacroNameWithContent(t, w, PROJECT_DEPENDENCIES_VAR);
    }

    if (posServiceDependencies) {
      final StringWriter w = new StringWriter(10*1024);
      final Sink sink = new ConfluenceSink(w);

      final ServiceDependencyHistoryRenderer posServiceVersionRenderer = new ServiceDependencyHistoryRenderer(sink,
        project, gitPosServiceSinceTagName, serviceDocumentationMap, getLog());

      posServiceVersionRenderer.render();

      replaceMacroNameWithContent(t, w, POS_SERVICE_DEPENDENCIES);

      {
        final StringWriter _w = new StringWriter(10*1024);
        final Sink _sink = new ConfluenceSink(_w);

        final CloudServiceChangelogRenderer cloudChangelogRenderer = new CloudServiceChangelogRenderer(_sink,
          posServiceVersionRenderer.getChangedServices(), getLog());

        cloudChangelogRenderer.render();

        replaceMacroNameWithContent(t, _w, POS_CLOUD_CHANGELOG);
      }

    }

    /////////////////////////////////////////////////////////////////
    // CHANGELOG JIRA ISSUES
    /////////////////////////////////////////////////////////////////
    if (gitLogJiraIssuesEnable) {
      final StringWriter w = new StringWriter(10*1024);
      final Sink sink = new ConfluenceSink(w);
      //final Sink sink = getSink();
      String currentVersion = project.getVersion();

      GitLogJiraIssuesRenderer gitLogJiraIssuesRenderer = new GitLogJiraIssuesRenderer(sink,
        gitLogSinceTagName,
        gitLogUntilTagName,
        gitLogJiraProjectKeyList,
        currentVersion,
        gitLogCalculateRuleForSinceTagName,
        gitLogTagNamesPattern,
        gitLogGroupByVersions,
        getLog());
      gitLogJiraIssuesRenderer.render();

      gitLogSinceTagName = gitLogJiraIssuesRenderer.getGitLogSinceTagName();

      replaceMacroNameWithContent(t, w, GITLOG_JIRA_ISSUES_VAR);

      try {
        if (gitLogSinceTagName == null) {
          gitLogSinceTagName = "beginning of gitlog";
        }
        getProperties().put(GITLOG_SINCE_TAG_NAME, gitLogSinceTagName); // to share with children
        t.setVariable(GITLOG_SINCE_TAG_NAME, gitLogSinceTagName);
      } catch (VariableNotDefinedException e) {
        getLog().debug(String.format("variable %s not defined in template", GITLOG_SINCE_TAG_NAME));
      }

    }

    final String wiki = t.generateOutput();

    super.confluenceExecute(new ConfluenceTask() {

      @Override
      public void execute(Confluence confluence) throws Exception {

        if (!isSnapshot() && isRemoveSnapshots()) {
          final String snapshot = title.concat("-SNAPSHOT");
          getLog().info(String.format("removing page [%s]!", snapshot));
          boolean deleted = ConfluenceUtils.removePage(confluence, getSpaceKey(), getParentPageTitle(), snapshot);

          if (deleted) {
            getLog().info(String.format("Page [%s] has been removed!", snapshot));
          }
        }

        Page confluencePage = ConfluenceUtils.getOrCreatePage(confluence, getSpaceKey(), getParentPageTitle(), title);

        confluencePage.setContent(wiki);

        confluencePage = confluence.storePage(confluencePage);

        for (String label : site.getHome().getComputedLabels()) {

          confluence.addLabelByName(label, Long.parseLong(confluencePage.getId()));
        }

        generateChildren(confluence, site.getHome(), confluencePage, getSpaceKey(), title, title);

      }
    });

  }

  private void replaceMacroNameWithContent(final MiniTemplator t, final StringWriter w, final String templateMacro) {
    try {
      final String content = w.toString();
      getProperties().put(templateMacro, content); // to share with children
      t.setVariable(templateMacro, content);

    } catch (VariableNotDefinedException e) {
      getLog().info(String.format("variable %s not defined in template", templateMacro));
    }
  }

  private ReportingResolutionListener resolveProject() {
    Map managedVersions = null;
    try {
      managedVersions = createManagedVersionMap(project.getId(), project.getDependencyManagement());
    } catch (ProjectBuildingException e) {
      getLog().error("An error occurred while resolving project dependencies.", e);
    }

    ReportingResolutionListener listener = new ReportingResolutionListener();

    try {
      collector.collect(project.getDependencyArtifacts(), project.getArtifact(), managedVersions,
        localRepository, project.getRemoteArtifactRepositories(), artifactMetadataSource, null,
        Collections.singletonList(listener));
    } catch (ArtifactResolutionException e) {
      getLog().error("An error occurred while resolving project dependencies.", e);
    }

    return listener;
  }

  private Map createManagedVersionMap(String projectId, DependencyManagement dependencyManagement) throws
    ProjectBuildingException {
    Map map;
    if (dependencyManagement != null && dependencyManagement.getDependencies() != null) {
      map = new HashMap();
      for (Dependency d : dependencyManagement.getDependencies()) {
        try {
          VersionRange versionRange = VersionRange.createFromVersionSpec(d.getVersion());
          Artifact artifact = factory.createDependencyArtifact(d.getGroupId(), d.getArtifactId(),
            versionRange, d.getType(), d.getClassifier(),
            d.getScope());
          //noinspection unchecked
          map.put(d.getManagementKey(), artifact);
        } catch (InvalidVersionSpecificationException e) {
          throw new ProjectBuildingException(projectId, "Unable to parse version '" + d.getVersion()
                                                        + "' for dependency '" + d.getManagementKey() + "': " + e.getMessage(), e);
        }
      }
    } else {
      map = Collections.EMPTY_MAP;
    }
    return map;
  }

  public String getDescription(Locale locale) {
    return "confluence";
  }

  public String getOutputName() {
    return "confluence";
  }

  public String getName(Locale locale) {
    return "confluence";
  }

/////////////////////////////////////////////////////////
///    
/// PLUGIN SECTION
///    
/////////////////////////////////////////////////////////
  //@Parameter( defaultValue="${project.build.directory}/generated-site/confluence",required=true )
  //private String outputDirectory;

  @Parameter(defaultValue = "${localRepository}", required = true, readonly = true)
  private ArtifactRepository local;

  /**
   * The set of dependencies for the current project
   *
   * @since 3.0
   */
  @Parameter(defaultValue = "${project.artifacts}", required = true, readonly = true)
  private Set<Artifact> dependencies;

  /**
   * List of Remote Repositories used by the resolver
   *
   * @since 3.0
   */
  @Parameter(defaultValue = "${project.remoteArtifactRepositories}", required = true, readonly = true)
  private List<ArtifactRepository> remoteRepos;

  /**
   * Mojo scanner tools.
   */
  //@MojoComponent
  @Component
  protected MojoScanner mojoScanner;

  private static List<ComponentDependency> toComponentDependencies(List<Dependency> dependencies) {
    //return PluginUtils.toComponentDependencies( dependencies )
    return GeneratorUtils.toComponentDependencies(dependencies);
  }

  @SuppressWarnings("unchecked")
  private void generatePluginReport(final Site site, Locale locale) throws MojoExecutionException {

    String goalPrefix = PluginDescriptor.getGoalPrefixFromArtifactId(project.getArtifactId());
    final PluginDescriptor pluginDescriptor = new PluginDescriptor();
    pluginDescriptor.setGroupId(project.getGroupId());
    pluginDescriptor.setArtifactId(project.getArtifactId());
    pluginDescriptor.setVersion(project.getVersion());
    pluginDescriptor.setGoalPrefix(goalPrefix);

    try {
      java.util.List deps = new java.util.ArrayList();

      deps.addAll(toComponentDependencies(project.getRuntimeDependencies()));
      deps.addAll(toComponentDependencies(project.getCompileDependencies()));

      pluginDescriptor.setDependencies(deps);
      pluginDescriptor.setDescription(project.getDescription());

      PluginToolsRequest request = new DefaultPluginToolsRequest(project, pluginDescriptor);
      request.setEncoding(getEncoding());
      request.setLocal(local);
      request.setRemoteRepos(remoteRepos);
      request.setSkipErrorNoDescriptorsFound(false);
      request.setDependencies(dependencies);

      try {

        mojoScanner.populatePluginDescriptor(request);

      } catch (InvalidPluginDescriptorException e) {
        // this is OK, it happens to lifecycle plugins. Allow generation to proceed.
        getLog().warn(String.format("Plugin without mojos. %s\nMojoScanner:%s", e.getMessage(), mojoScanner.getClass()));

      }

      // Generate the plugin's documentation
      super.confluenceExecute(new ConfluenceTask() {

        @Override
        public void execute(Confluence confluence) throws Exception {

          outputDirectory.mkdirs();

          getLog().info("speceKey=" + getSpaceKey() + " parentPageTitle=" + getParentPageTitle());
          Page confluencePage = confluence.getPage(getSpaceKey(), getParentPageTitle());

          Generator generator =
            new PluginConfluenceDocGenerator(ConfluenceDeployMojo.this,
              confluence,
              confluencePage,
              templateWiki); /*PluginXdocGenerator()*/

          PluginToolsRequest request =
            new DefaultPluginToolsRequest(project, pluginDescriptor);

          generator.execute(outputDirectory, request);

          for (String label : site.getHome().getComputedLabels()) {

            confluence.addLabelByName(label, Long.parseLong(confluencePage.getId()));
          }

          // Issue 32
          final String title = getTitle();
          //String title = project.getArtifactId() + "-" + project.getVersion();

          generateChildren(confluence,
            site.getHome(),
            confluencePage,
            getSpaceKey(),
            title,
            title);
          //generateChildren(confluence, getSpaceKey(), title, title);

        }
      });

      // Write the overview
      //PluginOverviewRenderer r = new PluginOverviewRenderer( getSink(), pluginDescriptor, locale );
      //r.render();
    } catch (ExtractionException e) {
      throw new MojoExecutionException(
        String.format("Error extracting plugin descriptor: %s",
          e.getLocalizedMessage()),
        e);
    }
  }

}
