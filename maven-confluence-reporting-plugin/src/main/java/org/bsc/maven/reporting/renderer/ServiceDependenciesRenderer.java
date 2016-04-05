package org.bsc.maven.reporting.renderer;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.AbstractMavenReportRenderer;
import org.bsc.maven.confluence.plugin.ReportingResolutionListener;
import org.codehaus.plexus.i18n.I18N;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @author Sorrentino
 */
public class ServiceDependenciesRenderer extends AbstractMavenReportRenderer {

  private final Locale locale;
  private final ReportingResolutionListener listener;
  private final MavenProject project;
  private final ArtifactFactory factory;
  private final I18N i18n;
  private final Log log;

  /**
   * @param sink
   * @param project
   * @param locale
   * @param listener
   */
  public ServiceDependenciesRenderer(Sink sink,
                                     MavenProject project,
                                     ArtifactFactory factory,
                                     I18N i18n,
                                     Locale locale,
                                     ReportingResolutionListener listener,
                                     Log log) {
    super(sink);

    this.project = project;
    this.locale = locale;
    this.listener = listener;
    this.i18n = i18n;
    this.factory = factory;
    this.log = log;
  }

  public String getTitle() {
    return getReportString("report.dependencies.title");
  }

  public void renderBody() {
    // Dependencies report

    List<?> dependencies = listener.getRootNode().getChildren();

    if (dependencies.isEmpty()) {
      return;
    }

//        startSection( getTitle() );

    // collect dependencies by scope
    Map dependenciesByScope = getDependenciesByGroup(dependencies);

    final List<Artifact> coreArtifacts = (List<Artifact>) dependenciesByScope.get("at.merkur.pos.core");
    final List<String> coreArtifactHeaders = new ArrayList<String>(coreArtifacts.size());

    for (Artifact a : coreArtifacts) {
      coreArtifactHeaders.add(a.getArtifactId());
    }
    startSection("Core Modules");
    renderDependencies(coreArtifacts, coreArtifactHeaders.toArray(new String[]{}));
    endSection();

    final List<Artifact> serviceArtifacts = (List<Artifact>) dependenciesByScope.get("at.merkur.pos.service");
    final List<String> serviceArtifactHeaders = new ArrayList<String>(serviceArtifacts.size());

    for (Artifact a : serviceArtifacts) {
      serviceArtifactHeaders.add(a.getArtifactId());
    }

    startSection("Services");
    renderDependencies(serviceArtifacts, serviceArtifactHeaders.toArray(new
      String[]{}));
    endSection();
  }

  private Map getDependenciesByGroup(final List dependencies) {
    Map dependenciesByGroup = new HashMap();
    for (Iterator i = dependencies.iterator(); i.hasNext(); ) {
      final ReportingResolutionListener.Node node = (ReportingResolutionListener.Node) i.next();
      final Artifact artifact = node.getArtifact();

      List multiValue = (List) dependenciesByGroup.get(artifact.getGroupId());
      if (multiValue == null) {
        multiValue = new ArrayList();
      }
      multiValue.add(artifact);
      dependenciesByGroup.put(artifact.getGroupId(), multiValue);
    }
    return dependenciesByGroup;
  }

  private void renderDependencies(List<Artifact> artifacts, String[] tableHeader) {
    startTable();
    tableHeader(tableHeader);

    final List<String> tableRow = new ArrayList<String>(tableHeader.length);
    for (final Artifact a : artifacts) {
      tableRow.add(a.getVersion());
    }
    tableRow(tableRow.toArray(new String[]{}));
    endTable();
  }

  private String getReportString(String key) {
    return i18n.getString("project-info-report", locale, key);
  }

}
