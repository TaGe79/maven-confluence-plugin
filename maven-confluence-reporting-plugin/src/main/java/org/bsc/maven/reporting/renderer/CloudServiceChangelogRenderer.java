package org.bsc.maven.reporting.renderer;

import at.merkur.pos.infrastructure.CloudChangelogCollector;
import org.apache.commons.io.IOUtils;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.reporting.AbstractMavenReportRenderer;
import org.bsc.maven.reporting.model.Site;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 */
public class CloudServiceChangelogRenderer extends AbstractMavenReportRenderer {

  static final Pattern findJiraIssueId = Pattern.compile("(\\[[A-Z]{4}-[0-9]{3,4}\\])");

  private final Set<String> changedArtifactIds;
  private final Log log;

  public CloudServiceChangelogRenderer(final Sink sink, Set<String> changedArtifactIds, Log log) {
    super(sink);
    this.changedArtifactIds = changedArtifactIds;
    this.log = log;
  }

  @Override
  public String getTitle() {
    return "Changelog";
  }

  @Override
  protected void renderBody() {
    final List<String> serviceModuleNames = new ArrayList<String>(changedArtifactIds.size());
    for (final String artifactId : changedArtifactIds) {
      final String serviceName = artifactId.replaceAll("-", ".").replace("back", "");
      serviceModuleNames.add(serviceName);
      log.info(String.format("Seeking changelog for upgraded service: %s", serviceName));
    }

    try {
      CloudChangelogCollector ccc = new CloudChangelogCollector(true, serviceModuleNames);

      final InputStream markdownStream = Site.processMarkdown(new ByteArrayInputStream(ccc.createChangelogContent()
                                                                                          .getBytes()));

      final StringWriter sw = new StringWriter();
      IOUtils.copy(markdownStream, sw);
      sink.paragraph();
      final String changelogMarkdownString = sw.toString();
      sink.text(changelogMarkdownString);
      sink.paragraph_();

      final Matcher matcher = findJiraIssueId.matcher(changelogMarkdownString);
      final StringBuilder sb = new StringBuilder();
      sb.append("----\n");
      sb.append("h2. Mentioned Jira Issues\n");
      log.info("Collecting jira issues from changelog");
      while (matcher.find()) {
        final String jiraKey = matcher.group().replace("[", "").replace("]", "");
        log.info("+ " + jiraKey);
        sb.append("{jira:").append(jiraKey).append("}\\\\\n");
      }
      sink.rawText(sb.toString());
    } catch (Exception e) {
      e.printStackTrace();
    }

  }
}
