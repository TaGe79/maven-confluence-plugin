package org.bsc.maven.reporting.renderer;

import com.github.qwazer.mavenplugins.gitlog.GitLogUtil;
import org.apache.commons.io.IOUtils;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.doxia.util.HtmlTools;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.AbstractMavenReportRenderer;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * @author ar
 * @since Date: 01.05.2015
 */
public class ServiceDependencyHistoryRenderer extends AbstractMavenReportRenderer {

  private static final class CloudPomVersionComparator implements Comparator<String> {
    private final int ASC = -1;
    private final int DESC = 1;

    private final int direction;

    private CloudPomVersionComparator(boolean descending) {
      this.direction = descending ? DESC : ASC;
    }

    @Override
    public int compare(final String o1, final String o2) {
      final String[] o1VersionString = o1.replace("cloud-pom-", "").split("\\.");
      final String[] o2VersionString = o2.replace("cloud-pom-", "").split("\\.");
      final int major = Integer.parseInt(o2VersionString[0]) - Integer.parseInt(o1VersionString[0]);
      if (major != 0) {
        return direction*major;
      }

      final int minor = Integer.parseInt(o2VersionString[1]) - Integer.parseInt(o1VersionString[1]);
      if (minor != 0) {
        return direction*minor;
      }

      final int rev = Integer.parseInt(o2VersionString[2]) - Integer.parseInt(o1VersionString[2]);
      if (rev != 0) {
        return direction*rev;
      }

      return 0;
    }

    public static CloudPomVersionComparator ASC() {
      return new CloudPomVersionComparator(false);
    }

    public static CloudPomVersionComparator DESC() {
      return new CloudPomVersionComparator(true);
    }
  }

  private final Log log;
  private String gitLogSinceTagName;
  private MavenProject rootProject;
  private Properties serviceDocumentationMap;
  private Set<String> changedServices = null;

  /**
   * Default constructor.
   */
  public ServiceDependencyHistoryRenderer(Sink sink, MavenProject rootProject, String gitLogSinceTagName,
                                          Properties serviceDocumentationMap,
                                          Log log) {
    super(sink);

    this.gitLogSinceTagName = gitLogSinceTagName;
    this.log = log;
    this.rootProject = rootProject;
    this.serviceDocumentationMap = serviceDocumentationMap;
  }

  private Set<String> formatPomFilesToString(Map<String, String> pomFiles) throws IOException,
    XmlPullParserException {

    sink.section2();
    sink.sectionTitle2();
    final String chapterName = "Cloud Dependency History";
    sink.text(chapterName);
    sink.sectionTitle2_();
    this.sink.anchor(HtmlTools.encodeId(chapterName));
    this.sink.anchor_();

    final Set<String> tableHeader = new HashSet<String>();
    final Map<String, Map<String, String>> versionDeps = new HashMap<String, Map<String, String>>();
    final ArrayList<String> cloudVersions = new ArrayList<String>(pomFiles.keySet());
    Collections.sort(cloudVersions, CloudPomVersionComparator.ASC());

    for (final String cloudVersion : cloudVersions) {
      final String pomFile = pomFiles.get(cloudVersion);
      log.info(String.format("Processing cloud pom of version %s", cloudVersion));

      MavenXpp3Reader mavenreader = new MavenXpp3Reader();
      final Model pomModel = mavenreader.read(IOUtils.toInputStream(pomFile, "UTF-8"));

      pomModel.setDependencyManagement(rootProject.getModel().getDependencyManagement());
      final MavenProject proj = new MavenProject(pomModel);

      try {
        @SuppressWarnings("unchecked") List<Dependency> dependencies = proj.getDependencies();
        if (dependencies.isEmpty()) {
          log.warn(String.format("No dependencies found in pom for %s", cloudVersion));
          continue;
        }

        log.info(String.format("Mapping dependencies for pom of version %s", cloudVersion));
        final Map<String, String> nameToArtifactMap = new HashMap<String, String>();
        for (final Dependency dep : dependencies) {
          tableHeader.add(dep.getArtifactId());

          final String depVer = dep.getVersion().startsWith("${") ?
            pomModel.getProperties().getProperty(dep.getVersion().replace("${", "").replace("}", ""), "?") : dep.getVersion();
          nameToArtifactMap.put(dep.getArtifactId(), depVer);
        }
        versionDeps.put(cloudVersion, nameToArtifactMap);
      } catch (Exception e) {
        log.error(String.format("Can not determine artifact versions for %s", cloudVersion));
        e.printStackTrace();
      }
    }

    startTable();

    final ArrayList<String> headList = new ArrayList<String>(tableHeader);
    Collections.sort(headList);
    decorateArtifactNamesWithConfluenceDocLink(headList);

    headList.add(0, "Cloud version");
    tableHeader(headList.toArray(new String[]{}));

    // Iterate from oldest to current cloud versions
    final Map<String, String> artifactVersionsBuffer = new HashMap<String, String>();
    final String actualVersion = cloudVersions.get(cloudVersions.size() - 1);
    log.info(String.format("Latest cloud version should be: %s", actualVersion));
    final Set<String> changedServices = new HashSet<String>();

    final Map<String, List<String>> completeTableRows = new HashMap<String, List<String>>(cloudVersions.size());

    for (final String cloudVersion : cloudVersions) {
      final Map<String, String> cloudDependencies = versionDeps.get(cloudVersion);
      if (cloudDependencies == null) {
        log.info(String.format("No dependencies found for cloud version: %s", cloudVersion));
        continue;
      }
      final List<String> tableRow = new ArrayList<String>(tableHeader.size());
      tableRow.add("*" + cloudVersion.replace("cloud-pom-", "") + "*");
      for (final String dependencyArtifactId : tableHeader) {
        final String version = cloudDependencies.get(dependencyArtifactId);
        final String lastVersion = artifactVersionsBuffer.get(dependencyArtifactId);
        if (version != null && !version.equals(lastVersion)) {
          tableRow.add(" {color:red}" + version + "{color} ");
          if (cloudVersion.equals(actualVersion)) {
            changedServices.add(dependencyArtifactId);
            log.info(String.format("CLOUD %s - Service changed: %s", cloudVersion, dependencyArtifactId));
          }
          artifactVersionsBuffer.put(dependencyArtifactId, version);
        } else {
          tableRow.add(version == null ? "---" : version);
        }
      }
      completeTableRows.put(cloudVersion, tableRow);
    }

    // Display from current to oldest cloud versions
    Collections.sort(cloudVersions, CloudPomVersionComparator.DESC());
    for (final String cloudVersion : cloudVersions) {
      final List<String> strings = completeTableRows.get(cloudVersion);
      if (strings == null) {
        continue;
      }
      tableRow(strings.toArray(new String[]{}));
    }
    endTable();

    sink.section2_();

    return changedServices;
  }

  private void decorateArtifactNamesWithConfluenceDocLink(final ArrayList<String> headList) {
    for (int i = 0; i < headList.size(); i++) {
      final String artifactName = headList.get(i);
      final String serviceDocRef = (String) this.serviceDocumentationMap.get(artifactName);
      if (serviceDocRef != null) {
        headList.set(i, "[" + artifactName + "|" + serviceDocRef + "]");
        continue;
      }

      if (!artifactName.startsWith("service-")) {
        continue;
      }

      final String serviceName = artifactName.replace("service-", "").replace("back", "");

      headList.set(i, "[" + artifactName + "|" +
                      serviceName.substring(0, 1).toUpperCase() + serviceName.substring(1) + " Service]");

    }
  }

  @Override
  protected void tableRow(final String[] content) {
    this.sink.tableRow();
    if (content != null) {
      for (int i = 0; i < content.length; ++i) {
        this.tableCell(content[i], true);
      }
    }

    this.sink.tableRow_();
  }

  @Override
  public String getTitle() {
    return "";
  }

  @Override
  protected void renderBody() {

    Repository repository = null;
    try {
      log.debug("Try to open git repository.");
      repository = GitLogUtil.openRepository();
      log.info(String.format("GIT Repository: %s", repository));
    } catch (Exception e) {
      log.warn("cannot open git repository  with error " + e);
    }
    log.debug("Try to open load version tag list.");
    final List<String> versionTagList = new ArrayList<String>(GitLogUtil
      .loadVersionTagList(repository, "^cloud-pom-.*"));
    Collections.sort(versionTagList);
    if (gitLogSinceTagName == null || gitLogSinceTagName.isEmpty()) {
      log.warn("gitLogSinceTagName is not specified and cannot be calculated via calculateRuleForSinceTagName");
    } else {
      final int idx = versionTagList.indexOf(gitLogSinceTagName);
      if (idx == -1) {
        log.warn(String.format("Can not find git tag: %s", gitLogSinceTagName));
      } else {
        for (int i = 0; i < idx; i++) {
          versionTagList.set(i, "");
        }
      }
    }

    final List<String> filteredVersionTagList = new ArrayList<String>(versionTagList.size());
    for (final String versionTag : versionTagList) {
      if (versionTag.isEmpty()) {
        continue;
      }
      filteredVersionTagList.add(versionTag);
    }

    Map<String, String> pomFiles = new HashMap<String, String>();
    try {
      pomFiles = GitLogUtil.getPomFilesFromCommits(repository, filteredVersionTagList);
    } catch (Exception e) {
      log.error("cannot render service version history with error " + e, e);
    }
    log.info(String.format("Found %d POM Files", pomFiles.size()));

    try {
      this.changedServices = formatPomFilesToString(pomFiles);
    } catch (Exception ex) {
      ex.printStackTrace();
      sink.rawText("ERROR: " + ex.getMessage());
    }
  }

  public Set<String> getChangedServices() {
    return this.changedServices;
  }
}
