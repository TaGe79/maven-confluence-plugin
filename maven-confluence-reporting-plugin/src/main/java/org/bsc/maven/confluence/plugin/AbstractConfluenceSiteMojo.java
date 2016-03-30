/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.bsc.maven.confluence.plugin;

import java.io.File;
import java.io.FileFilter;
import java.net.URISyntaxException;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import org.apache.maven.plugins.annotations.Parameter;
import org.bsc.maven.plugin.confluence.ConfluenceUtils;
import org.bsc.maven.reporting.model.Site;
import org.bsc.maven.reporting.model.SiteFactory;
import org.codehaus.swizzle.confluence.Attachment;
import org.codehaus.swizzle.confluence.Confluence;
import org.codehaus.swizzle.confluence.Page;
import org.codehaus.swizzle.confluence.SwizzleException;
import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

/**
 *
 * @author bsorrentino
 */
public abstract class AbstractConfluenceSiteMojo extends AbstractConfluenceMojo implements SiteFactory {

    /**
     * site xml descriptor
     * @since 3.3.0
     */
    @Parameter(defaultValue = "${basedir}/src/site/confluence/site.xml")
    protected java.io.File siteDescriptor;

    /**
     * 
     * @return 
     */
    public File getSiteDescriptor() {
        return siteDescriptor;
    }
    
    
    protected boolean isSiteDescriptorValid() {
        return ( siteDescriptor!=null  && siteDescriptor.exists() && siteDescriptor.isFile());   
    }
    
    /**
     * 
     * @param page
     * @param source 
     */
    private void setPageUriFormFile( Site.Page page, java.io.File source ) {
        if( page == null ) {
            throw new IllegalArgumentException( "page is null!");
        }
        
        if (source != null && source.exists() && source.isFile() && source.canRead() ) {
            page.setUri(source.toURI());
        }
        else {
            try {
                java.net.URL sourceUrl = getClass().getClassLoader().getResource("defaultTemplate.confluence");
                page.setUri( sourceUrl.toURI() );
            } catch (URISyntaxException ex) {
                // TODO log
            }
        }
        
    }
    
    static class AttachmentPair {
        
        final Site.Attachment model;
        final Attachment object;

        public AttachmentPair(Site.Attachment model, Attachment object) {
            this.model = model;
            this.object = object;
        }
        public AttachmentPair() {
            this( null, null);
        }
        
        public final boolean isValid() {
            return (model!=null && object !=null );
        }
    }
    /**
     * 
     * @param page
     * @param confluence
     * @param confluencePage 
     */
    private void generateAttachments( Site.Page page,  final Confluence confluence, final Page confluencePage) /*throws MavenReportException*/ {

        getLog().info(String.format("generateAttachments pageId [%s]", confluencePage.getId()));

        Observable.from(page.getAttachments() )
                .map(  new Func1<Site.Attachment, AttachmentPair>() {
                    @Override
                    public AttachmentPair call(final Site.Attachment attachment) {
                        
                        Attachment a = null;
                        
                        try {
                            
                            a = confluence.getAttachment(confluencePage.getId(), attachment.getName(), attachment.getVersion());
                            
                            
                        } catch (SwizzleException ex) {
                            getLog().warn(String.format("Error getting attachment [%s] from confluence: [%s]", attachment.getName(), ex.getMessage()));

                            a = new Attachment();
                            a.setFileName(attachment.getName());
                            a.setContentType(attachment.getContentType());
                        }
                        
                        return new AttachmentPair(attachment,a);
                    }
                    
                })
                .filter( new Func1<AttachmentPair, Boolean>() {
                    @Override
                    public Boolean call(AttachmentPair tuple) {
                        
                        if( tuple.isValid() ) {
                        
                            final java.util.Date date = tuple.object.getCreated();

                            if (date == null) {
                                getLog().warn(String.format("creation date of attachments [%s] is undefined. It will be replaced! ", tuple.object.getFileName()));
                                return true;
                            } 

                            if (tuple.model.hasBeenUpdatedFrom(date)) {
                                getLog().info(String.format("attachment [%s] is more recent than the remote one [%Tc]. It will be replaced! ", 
                                            tuple.object.getFileName(),
                                            date
                                            ));
                                return true;
                            }

                            getLog().info(String.format("attachment [%s] skipped! no updated detected", tuple.object.getFileName()));
                        
                        }
                        else {
                            getLog().info(String.format("attachment [%s] skipped! no updated detected", tuple.model.getName()));
                            
                        }

                        return false;
                    }
                    
                })
                .onExceptionResumeNext( Observable.just(new AttachmentPair()) )
                //.subscribeOn(Schedulers.io())
                .subscribe( new Action1<AttachmentPair>() {
                    @Override
                    public void call(AttachmentPair t) {
                        t.object.setComment( t.model.getComment());

                        try {
                            ConfluenceUtils.addAttchment(confluence, confluencePage, t.object, t.model.getUri().toURL() );
                        } catch (Exception e) {
                            getLog().error(String.format("Error uploading attachment [%s] ", t.model.getName()), e);
                        }
                    }                
                })
                ;

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

            final Page confluencePage = generateChild(confluence, child, spaceKey, parentPage.getName(), titlePrefix);
            
            if( confluencePage != null  ) {

                generateChildren(confluence, child, confluencePage, spaceKey, child.getName(), titlePrefix );    
            }
            
        }
 
    }

    /**
     * 
     * @param folder
     * @param page
     * @return 
     */
    protected boolean navigateAttachments( java.io.File folder,  Site.Page page) /*throws MavenReportException*/ {

        if (folder.exists() && folder.isDirectory()) {

            java.io.File[] files = folder.listFiles();

            if (files != null && files.length > 0) {

                for (java.io.File f : files) {

                    if (f.isDirectory() || f.isHidden()) {
                        continue;
                    }

                    Site.Attachment a = new Site.Attachment();

                    a.setName(f.getName());
                    a.setUri(f.toURI());

                    page.getAttachments().add(a);
                }
            }

            return true;
        }
        
        return false;
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

                    if( file.isHidden() || file.getName().charAt(0)=='.') {
                        return false ;
                    }

                    
                    if( file.isDirectory() ) {
                    
                        if( navigateAttachments(file, parentChild)) {
                            return false;
                        }
            
                        Site.Page child = new Site.Page();

                        child.setName(file.getName());
                        setPageUriFormFile(child, new java.io.File(file,templateWiki.getName()) );
 
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
                    setPageUriFormFile(child, file );
                    
                    parentChild.getChildren().add(child);
                    
                    return false;

                }
            });
        }

    }
   
    @Override
    public Site createFromFolder() {
        
        final Site result = new Site();
        
        result.getLabels().addAll( super.getLabels());
        
        final Site.Page home = new Site.Page();
        
        
        home.setName(getTitle());
        
        setPageUriFormFile(home, templateWiki);
        
        result.setHome( home );
        

        navigateAttachments(getAttachmentFolder(), home);
        
        if (getChildrenFolder().exists() && getChildrenFolder().isDirectory()) {

            getChildrenFolder().listFiles(new FileFilter() {

                @Override
                public boolean accept(File file) {


                    if( file.isHidden() || file.getName().charAt(0)=='.') return false ;

                    if( file.isDirectory() ) {
                       
                        Site.Page parentChild = new Site.Page();

                        parentChild.setName(file.getName());
                        setPageUriFormFile(parentChild, new java.io.File(file,templateWiki.getName()) );

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
                    setPageUriFormFile(child, file );

                    result.getHome().getChildren().add(child);

                    return false;

                }
            });
        }
        
        return result;
    }

    /**
     * 
     * @return 
     */
    @Override
    public Site createFromModel() {
        
        Site site = null;
        
        if( !isSiteDescriptorValid() ) {
        
            getLog().warn( "siteDescriptor is not valid!" );
            
        }
        else {
            try {

                JAXBContext jc = JAXBContext.newInstance(Site.class);
                Unmarshaller unmarshaller = jc.createUnmarshaller();

                site = (Site) unmarshaller.unmarshal( siteDescriptor );

            } catch (JAXBException ex) {
                getLog().error("error creating site from model!", ex);

            }
        }
        return site;
    }
    
}
