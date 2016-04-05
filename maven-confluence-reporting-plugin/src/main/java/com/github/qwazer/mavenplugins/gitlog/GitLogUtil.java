package com.github.qwazer.mavenplugins.gitlog;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.IO;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Util methods for work with GIT repo
 *
 * @author ar
 * @since Date: 04.05.2015
 */
public class GitLogUtil {

  public static Repository openRepository() throws NoGitRepositoryException, IOException {
    Repository repository = null;
    try {
      repository = new RepositoryBuilder().findGitDir().build();
    } catch (IllegalArgumentException iae) {
      throw new NoGitRepositoryException();
    }
    return repository;
  }

  public static Set<String> loadVersionTagList(Repository repository, String versionTagNamePattern) {
    Set<String> versionTagList = new HashSet<String>();
    if (versionTagNamePattern != null) {
      versionTagList = new HashSet<String>();
      for (String tagName : repository.getTags().keySet()) {
        if (tagName.matches(versionTagNamePattern)) {
          versionTagList.add(tagName);
        }
      }
    } else {
      versionTagList = repository.getTags().keySet();
    }
    return versionTagList;
  }

  protected static RevCommit resolveCommitIdByTagName(Repository repository, String tagName) throws IOException,
    GitAPIException {
    if (tagName == null || tagName.isEmpty()) {
      return null;
    }
    RevCommit revCommit = null;
    Map<String, Ref> tagMap = repository.getTags();
    Ref ref = tagMap.get(tagName);
    if (ref != null) {
      RevWalk walk = new RevWalk(repository);
      //some reduce memory effors as described in jgit user guide
      walk.setRetainBody(false);
      ObjectId from;

      from = repository.resolve("refs/heads/master");
      if (from == null) {
        Git git = new Git(repository);
        String lastTagName = git.describe().call();
        from = repository.resolve("refs/tags/" + lastTagName);
      }
      ObjectId to = repository.resolve("refs/remotes/origin/master");

      if (from == null) {
        throw new IllegalStateException("cannot determinate start commit");
      }
      walk.markStart(walk.parseCommit(from));
      walk.markUninteresting(walk.parseCommit(to));
      try {

        RevObject revObject = walk.parseAny(ref.getObjectId());
        if (revObject != null) {
          revCommit = walk.parseCommit(revObject.getId());

        }

      } finally {
        walk.close();
      }

    }

    return revCommit;

  }

  public static Set<String> extractJiraIssues(Repository repository,
                                              String sinceTagName,
                                              String untilTagName,
                                              String pattern) throws IOException, GitAPIException {
    final Git git = new Git(repository);
    System.out.println(String.format("---> Extracting jira issues from %s [%s - %s]", git, sinceTagName, untilTagName));
    RevCommit startCommitId = resolveCommitIdByTagName(repository, sinceTagName);
    if (startCommitId == null) {
      throw new IOException("cannot resolveCommitIdByTagName by  " + sinceTagName);
    }
    ObjectId endCommitId = resolveCommitIdByTagName(repository, untilTagName);
    if (endCommitId == null) {
      endCommitId = repository.resolve(Constants.HEAD);
    }
    Iterable<RevCommit> commits = git.log().addRange(startCommitId, endCommitId).call();

    return extractJiraIssues(pattern, commits);
  }

  public static Map<String, String> getPomFilesFromCommits(Repository repository, List<String> versionTagList) throws
    IOException,
    GitAPIException {
    final Git git = new Git(repository);
    System.out.println(String.format("---> Extracting pom files from %s", git));

    List<RevCommit> commits = new ArrayList<RevCommit>();
    Map<String, String> pomFiles = new HashMap<String, String>();
    for (final String versionTag : versionTagList) {
      System.out.println(String.format("\t===> for version tag: %s", versionTag));
      final RevCommit commit = resolveCommitIdByTagName(repository, versionTag);
      commits.add(commit);

      if (commit == null) {
        throw new IOException("cannot getPomFilesFromCommits by  " + versionTag);
      }

      TreeWalk treeWalk = null;
      try {
        treeWalk = new TreeWalk(repository);
        treeWalk.addTree(commit.getTree());
        treeWalk.setRecursive(true);
        treeWalk.setFilter(PathFilter.create("pom.xml"));
        if (!treeWalk.next()) {
          throw new IllegalStateException("Did not find expected file 'pom.xml'");
        }

        final ObjectId objectId = treeWalk.getObjectId(0);
        final ObjectLoader loader = repository.open(objectId);

        pomFiles.put(versionTag, new String(IO.readWholeStream(loader.openStream(), (int) loader.getSize()).array()));

      } finally {
        if (treeWalk != null) {
          treeWalk.close();
        }
      }
    }

    return pomFiles;
  }

  public static LinkedHashMap<String, Set<String>> extractJiraIssuesByVersion(Repository repository,
                                                                              List<String> versionTagList,
                                                                              String pattern) throws IOException,
    GitAPIException {

    LinkedHashMap<String, Set<String>> linkedHashMap = new LinkedHashMap<String, Set<String>>();

    int lenght = versionTagList.size();
    for (int i = 0; i < lenght; i++) {
      String sinceTagName = versionTagList.get(i);
      String untilTagName = i + 1 > lenght - 1 ? null : versionTagList.get(i + 1);
      linkedHashMap.put(versionTagList.get(i), extractJiraIssues(repository, sinceTagName, untilTagName, pattern));
    }
    return linkedHashMap;
  }

  private static Set<String> extractJiraIssues(String pattern, Iterable<RevCommit> commits) {
    HashSet jiraIssues = new LinkedHashSet();  //preserve insertion order
    for (RevCommit commit : commits) {
//      System.out.println("---- GIT Commit: " + commit.getFullMessage());
      if (commit.getFullMessage().contains("git-subtree-dir")) {
//        System.out.println("---- GIT Commit: IGNORE subtree commits! xxxxxxx");
        continue;
      }
      jiraIssues.addAll(extractJiraIssuesFromString(commit.getFullMessage(), pattern));
    }

    return jiraIssues;
  }

  protected static List<String> extractJiraIssuesFromString(String s, String jiraIssuePattern) {

    Pattern p = Pattern.compile(jiraIssuePattern);
    Matcher m = p.matcher(s);
    List<String> list = new ArrayList<String>();
    while (m.find()) {
      list.add(m.group(0));
    }
    return list;

  }
}
