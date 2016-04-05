package org.bsc.maven.reporting.renderer;

import com.github.qwazer.mavenplugins.gitlog.GitLogUtil;
import org.apache.commons.io.IOUtils;
import org.apache.maven.doxia.sink.Sink;
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
import java.util.Set;

/**
 * @author ar
 * @since Date: 01.05.2015
 */
public class ServiceDependencyHistoryRenderer extends AbstractMavenReportRenderer {

  private final Log log;
  private String gitLogSinceTagName;
  private MavenProject rootProject;

  /**
   * Default constructor.
   */
  public ServiceDependencyHistoryRenderer(Sink sink, MavenProject rootProject, String gitLogSinceTagName, Log log) {
    super(sink);

    this.gitLogSinceTagName = gitLogSinceTagName;
    this.log = log;
    this.rootProject = rootProject;
  }

  private void formatPomFilesToString(Map<String, String> pomFiles) throws IOException,
    XmlPullParserException {

    startSection("Cloud Dependency History");
    final Set<String> tableHeader = new HashSet<String>();
    final Map<String, Map<String, String>> versionDeps = new HashMap<String, Map<String, String>>();
    final ArrayList<String> cloudVersions = new ArrayList<String>(pomFiles.keySet());
    Collections.sort(cloudVersions, new Comparator<String>() {
      @Override
      public int compare(final String o1, final String o2) {
        return o2.compareTo(o1);
      }
    });

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
    headList.add(0, "Cloud version");
    tableHeader(headList.toArray(new String[]{}));

    for (final String cloudVersion : cloudVersions) {
      final Map<String, String> cloudDependencies = versionDeps.get(cloudVersion);
      if (cloudDependencies == null) {
        continue;
      }
      final List<String> tableRow = new ArrayList<String>(tableHeader.size());
      tableRow.add("*" + cloudVersion.replace("cloud-pom-", "") + "*");
      for (final String dependencyArtifactId : tableHeader) {
        final String version = cloudDependencies.get(dependencyArtifactId);
        tableRow.add(version == null ? "---" : version);
      }
      tableRow(tableRow.toArray(new String[]{}));
    }
    endTable();

    endSection();
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
      formatPomFilesToString(pomFiles);
    } catch (Exception ex) {
      ex.printStackTrace();
      sink.rawText("ERROR: " + ex.getMessage());
    }
  }

}
