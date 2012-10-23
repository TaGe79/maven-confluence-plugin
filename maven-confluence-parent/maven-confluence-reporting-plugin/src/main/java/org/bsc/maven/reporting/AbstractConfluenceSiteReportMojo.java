/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.bsc.maven.reporting;

import java.io.File;
import java.io.FileFilter;
import java.net.URISyntaxException;
import org.bsc.maven.plugin.confluence.ConfluenceUtils;
import org.bsc.maven.reporting.model.Child;
import org.bsc.maven.reporting.model.Site;
import org.bsc.maven.reporting.model.SiteFactory;
import org.codehaus.swizzle.confluence.Attachment;
import org.codehaus.swizzle.confluence.Confluence;
import org.codehaus.swizzle.confluence.Page;

/**
 *
 * @author softphone
 */
public abstract class AbstractConfluenceSiteReportMojo extends AbstractConfluenceReportMojo implements SiteFactory {

    /**
     * 
     * @param page
     * @param confluence
     * @param confluencePage 
     */
    private void generateAttachments( Site.Page page,  Confluence confluence, Page confluencePage) /*throws MavenReportException*/ {

        getLog().info(String.format("generateAttachments pageId [%s]", confluencePage.getId()));

        for( Site.Attachment attachment : page.getAttachments() ) {

            Attachment confluenceAttachment = null;

            try {
                confluenceAttachment = confluence.getAttachment(confluencePage.getId(), attachment.getName(), attachment.getVersion());
            } catch (Exception e) {
                getLog().warn(String.format("Error getting attachment [%s] from confluence: [%s]", attachment.getName(), e.getMessage()));
            }

            if (confluenceAttachment != null) {


                java.util.Date date = confluenceAttachment.getCreated();

                if (date == null) {
                    getLog().warn(String.format("creation date of attachments [%s] is undefined. It will be replaced! ", confluenceAttachment.getFileName()));
                } else {
                    if (attachment.hasBeenUpdatedFrom(date)) {
                        getLog().info(String.format("attachment [%s] is more recent than the remote one. It will be replaced! ", confluenceAttachment.getFileName()));
                    } else {
                        getLog().info(String.format("attachment [%s] skipped! no updated detected", confluenceAttachment.getFileName()));
                        continue;

                    }
                }
            } else {
                confluenceAttachment = new Attachment();
                confluenceAttachment.setFileName(attachment.getName());
                confluenceAttachment.setContentType(attachment.getContentType());

            }

            confluenceAttachment.setComment( attachment.getComment());
            
            try {
                ConfluenceUtils.addAttchment(confluence, confluencePage, confluenceAttachment, attachment.getUri().toURL() );
            } catch (Exception e) {
                getLog().error(String.format("Error uploading attachment [%s] ", attachment.getName()), e);
            }

        }

    }
    
    
    /**
     * 
     * @param confluence
     * @param parentPage
     * @param confluenceParentPage
     * @param spaceKey
     * @param parentPageTitle
     * @param titlePrefix 
     */
    protected void generateChildren(    final Confluence confluence, 
                                        final Site.Page parentPage,
                                        final Page confluenceParentPage,  
                                        final String spaceKey, 
                                        final String parentPageTitle, 
                                        final String titlePrefix) 
    {

        getLog().info(String.format("generateChildren # [%d]", parentPage.getChildren().size()));

        
        generateAttachments(parentPage, confluence, confluenceParentPage);
        
        for( Site.Page child : parentPage.getChildren() ) {

            final Page confluencePage = generateChild(confluence, child, spaceKey, parentPageTitle, titlePrefix);
            
            if( confluencePage != null  ) {

                generateChildren(confluence, child, confluencePage, spaceKey, parentPageTitle, titlePrefix );    
            }
            
        }
 
    }
    
    /**
     * 
     * @param confluence
     * @param page 
     */
    protected void navigateAttachments( java.io.File folder,  Site.Page page) /*throws MavenReportException*/ {

        java.io.File[] files = folder.listFiles();

        if (files == null || files.length == 0) {
            return;
        }

        for (java.io.File f : files) {

            if (f.isDirectory() || f.isHidden()) {
                continue;
            }

            Site.Attachment a = new Site.Attachment();

            a.setName( f.getName() );
            a.setUri(  f.toURI() );
            
            page.getAttachments().add(a);

        }

    }
    
    /**
     * 
     * @param level
     * @param folder
     * @param parentChild 
     */
   protected void navigateChild( final int level, final java.io.File folder, final Site.Page parentChild ) /*throws MavenReportException*/ {

        if (folder.exists() && folder.isDirectory()) {

            folder.listFiles(new FileFilter() {

                @Override
                public boolean accept(File file) {

                    if( file.isHidden() || file.getName().charAt(0)=='.') return false ;

                    final java.io.File attachmentSubFolder = 
                                new java.io.File( file, getAttachmentFolder().getName() );
                    
                    if( file.isDirectory() ) {
            
                        Site.Page child = new Site.Page();

                        child.setName(file.getName());
                        child.setUri( new java.io.File(file,templateWiki.getName()).toURI());
 
                        navigateAttachments( attachmentSubFolder, child);
                        
                        parentChild.getChildren().add(child);
 
                        navigateChild( level+1, file, child );    
                       
                       return true;
                    }
                    
                    final String fileName = file.getName();

                    if (!file.isFile() || !file.canRead() || !fileName.endsWith( getFileExt() ) || fileName.equals(templateWiki.getName())) {
                        return false;
                    }

                    Site.Page child = new Site.Page();
                    final int extensionLen = getFileExt().length();

                    child.setName(fileName.substring(0, fileName.length() - extensionLen));
                    child.setUri(file.toURI());
                    
                    navigateAttachments( attachmentSubFolder, child);
                    
                    parentChild.getChildren().add(child);
                    
                    return false;

                }
            });
        }

    }
   
    public Site createFromFolder() {
        
        final Site result = new Site();
        
        result.getLabels().addAll(  super.getLabels());
        
        final Site.Page home = new Site.Page();
        
        home.setName("home");
        
        if (templateWiki == null || !templateWiki.exists()) {
            try {
                java.net.URL sourceUrl = getClass().getClassLoader().getResource("defaultTemplate.confluence");
                home.setUri( sourceUrl.toURI() );
            } catch (URISyntaxException ex) {
                // TODO log
            }
        }
        else {
            home.setUri(templateWiki.toURI());
        }
        
        result.setHome( home );
        
        result.getHome().getChildren().addAll( super.getChildren() );

        navigateAttachments(getAttachmentFolder(), home);
        
        if (getChildrenFolder().exists() && getChildrenFolder().isDirectory()) {

            getChildrenFolder().listFiles(new FileFilter() {

                @Override
                public boolean accept(File file) {


                    if( file.isHidden() || file.getName().charAt(0)=='.') return false ;

                    if( file.isDirectory() ) {
                       
                        Site.Page parentChild = new Site.Page();

                        parentChild.setName(file.getName());
                        parentChild.setUri( new java.io.File(file,templateWiki.getName()).toURI());

                        result.getHome().getChildren().add(parentChild);

                        navigateChild( 1, file, parentChild );    
                        
                        return false;
                    }
                     
                    final String fileName = file.getName();

                    if (!file.isFile() || !file.canRead() || !fileName.endsWith(getFileExt()) || fileName.equals(templateWiki.getName())) {
                        return false;
                    }

                    Site.Page child = new Site.Page();
                    
                    final int extensionLen = getFileExt().length();
                    
                    child.setName(fileName.substring(0, fileName.length() - extensionLen));
                    child.setUri(file.toURI());

                    result.getHome().getChildren().add(child);

                    return false;

                }
            });
        }
        
        return result;
    }

    public Site createFromModel() {
        return null;
    }
    
}