/*
 * The MIT License
 *
 * Copyright (c) 2015 Oleg Nenashev.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.ownership.model.folders;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.hudson.plugins.folder.Folder;
import com.synopsys.arc.jenkins.plugins.ownership.OwnershipDescription;
import com.synopsys.arc.jenkins.plugins.ownership.extensions.item_ownership_policy.AssignCreatorPolicy;
import com.synopsys.arc.jenkins.plugins.ownership.jobs.JobOwnerHelper;
import hudson.model.FreeStyleProject;
import hudson.model.User;
import hudson.remoting.Callable;
import hudson.security.ACL;
import hudson.security.SecurityRealm;
import java.util.Arrays;
import static org.hamcrest.Matchers.*;
import org.jenkinsci.plugins.ownership.config.InheritanceOptions;
import org.jenkinsci.plugins.ownership.model.OwnershipInfo;
import org.jenkinsci.plugins.ownership.test.util.OwnershipPluginConfigurer;
import org.jenkinsci.remoting.RoleChecker;
import static org.junit.Assert.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Stores tests of {@link AbstractFolder} ownership.
 * @author Oleg Nenashev
 */
public class FolderOwnershipTest {
    
    @Rule
    public JenkinsRule j = new JenkinsRule();
    
    public FolderOwnershipHelper ownershipHelper = FolderOwnershipHelper.getInstance();
    
    @Test
    public void ownershipInfoShouldBeEmptyByDefault() throws Exception {
        Folder folder = j.jenkins.createProject(Folder.class, "myFolder");
        assertThat("Property should be injected by default", 
                FolderOwnershipHelper.getOwnerProperty(folder), notNullValue());
        assertThat("Property should be disabled by default", 
                FolderOwnershipHelper.getOwnerProperty(folder).getOwnership().isOwnershipEnabled(), equalTo(false));
        
        assertThat("Folder ownership helper should return the \"disabled\" description",
                ownershipHelper.getOwnershipDescription(folder), 
                equalTo(OwnershipDescription.DISABLED_DESCR));
    }
    
    @Test
    public void ownershipInfoShouldSurviveRoundtrip() throws Exception {
        Folder folder = j.jenkins.createProject(Folder.class, "myFolder");
        
        // Set ownership via API
        OwnershipDescription original = new OwnershipDescription(true, "ownerId", 
            Arrays.asList("coowner1, coowner2"));
        FolderOwnershipHelper.setOwnership(folder, original);
        
        assertThat("Folder ownership helper should return the configured value",
                ownershipHelper.getOwnershipDescription(folder), 
                equalTo(original));
        
        // Reload folder from disk and check the state
        folder.doReload();
        assertThat("Folder ownership helper should return the configured value after the reload",
                ownershipHelper.getOwnershipDescription(j.jenkins.getItemByFullName("myFolder", Folder.class)), 
                equalTo(original));
    }
    
    @Test
    @Issue("JENKINS-32359")
    public void ownershipFromLoadedFolderShouldSurviveRoundtrip() throws Exception {
        Folder folder = j.jenkins.createProject(Folder.class, "myFolder");
        
        // Drop the Ownership property injected by ItemListener.
        // We emulate the folder loaded from the instance with folders.
        // After that we save and reload the config in order to drop PersistedListOwner according to JENKINS-32359
        folder.getProperties().remove(FolderOwnershipProperty.class);
        folder.save();
        folder.doReload();
        
        // Set ownership via API
        // It should invoke save via the persisted list if JENKINS-32359 does not block it
        OwnershipDescription original = new OwnershipDescription(true, "ownerId", 
            Arrays.asList("coowner1, coowner2"));
        FolderOwnershipHelper.setOwnership(folder, original);
        assertThat("Folder ownership helper should return the configured value",
                ownershipHelper.getOwnershipDescription(folder), 
                equalTo(original));
        
        // Reload folder from disk and check the state
        folder.doReload();
        assertThat("Folder ownership helper should return the configured value after the reload",
                ownershipHelper.getOwnershipDescription(j.jenkins.getItemByFullName("myFolder", Folder.class)), 
                equalTo(original));
    }
    
    @Test
    public void shouldSupportAssignCreatorPolicy() throws Exception {
        
        // Init security
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        User myUser = User.get("testUser");
        
        // Configure the policy
        OwnershipPluginConfigurer.forJenkinsRule(j)
                .withItemOwnershipPolicy(new AssignCreatorPolicy())
                .configure();
        
        // Create Item from the user account
        ACL.impersonate(myUser.impersonate(), new Callable<Void, Exception>() {
            @Override
            public Void call() throws Exception {
                j.jenkins.createProject(Folder.class, "myFolder");
                return null;
            }

            @Override
            public void checkRoles(RoleChecker checker) throws SecurityException {
                // do nothing
            }
        });
        
        // Retrieve item and verify it's status
        Folder folder = j.jenkins.getItemByFullName("myFolder", Folder.class);
        assertThat("Cannot locate folder 'myFolder'", folder, notNullValue());
        FolderOwnershipProperty ownerProperty = FolderOwnershipHelper.getOwnerProperty(folder);
        assertThat("Property should be injected by AssignCreatorPolicy",  ownerProperty, notNullValue());
        assertThat("Ownership should be enabled according to AssignCreatorPolicy", 
                ownerProperty.getOwnership().isOwnershipEnabled(), equalTo(true));
        assertThat("testUser should be automatically assigned as a Folder owner", 
                ownerProperty.getOwnership().getPrimaryOwnerId(), equalTo("testUser"));
        
        // Reload configs in order to verify the persistence
        j.jenkins.reload();
        Folder folderReloaded = j.jenkins.getItemByFullName("myFolder", Folder.class);
        FolderOwnershipProperty ownerPropertyReloaded = FolderOwnershipHelper.getOwnerProperty(folderReloaded);
        assertThat("testUser should be retained after the restart", 
                ownerPropertyReloaded.getOwnership().getPrimaryOwnerId(), equalTo("testUser"));
    }
    
    @Test
    public void ownershipShouldBeInheritedFromFolderByDefault() throws Exception {
        Folder folder = j.jenkins.createProject(Folder.class, "myFolder");
        FreeStyleProject project = folder.createProject(FreeStyleProject.class, "projectInFolder");
        
        // Set ownership via API
        OwnershipDescription original = new OwnershipDescription(true, "ownerId", 
            Arrays.asList("coowner1, coowner2"));
        FolderOwnershipHelper.setOwnership(folder, original);
        
        assertThat("Folder ownership helper should return the inherited value",
                JobOwnerHelper.Instance.getOwnershipDescription(project), 
                equalTo(original));
        
        
        // Reload folder from disk and check the state
        j.jenkins.reload();
        assertThat("Folder ownership helper should return the inherited value after the reload",
                JobOwnerHelper.Instance.getOwnershipDescription(
                        j.jenkins.getItemByFullName("myFolder/projectInFolder", FreeStyleProject.class)), 
                equalTo(original));
    }
    
    @Test
    public void ownershipShouldBeInheritedFromTopLevelFolderByDefault() throws Exception {
        Folder folder1 = j.jenkins.createProject(Folder.class, "folder1");
        Folder folder2 = folder1.createProject(Folder.class, "folder2");
        FreeStyleProject project = folder2.createProject(FreeStyleProject.class, "projectInFolder");
        
        // Set ownership via API
        OwnershipDescription original = new OwnershipDescription(true, "ownerId", 
            Arrays.asList("coowner1, coowner2"));
        FolderOwnershipHelper.setOwnership(folder1, original);
        
        assertThat("Folder ownership helper should return the inherited value",
                JobOwnerHelper.Instance.getOwnershipDescription(project), 
                equalTo(original));
        
        
        // Reload folder from disk and check the state
        j.jenkins.reload();
        OwnershipInfo ownershipInfo = JobOwnerHelper.Instance.getOwnershipInfo(
                j.jenkins.getItemByFullName("folder1/folder2/projectInFolder", FreeStyleProject.class));
        assertThat("Folder ownership helper should return the inherited value after the reload",
                ownershipInfo.getDescription(), equalTo(original));
        assertThat("OwnershipInfo should return the right reference", 
                (Object)ownershipInfo.getSource().getItem(), equalTo((Object)j.jenkins.getItemByFullName("folder1")));
    }
    
    @Test
    public void ownershipShouldNotBeInheritedFromTopLevelFolderIfDisabled() throws Exception {
        Folder folder1 = j.jenkins.createProject(Folder.class, "folder1");
        Folder folder2 = folder1.createProject(Folder.class, "folder2");
        FreeStyleProject project = folder2.createProject(FreeStyleProject.class, "projectInFolder");
        
        // Set ownership via API
        OwnershipDescription original = new OwnershipDescription(true, "ownerId", Arrays.asList("coowner1, coowner2"));
        FolderOwnershipHelper.setOwnership(folder1, original); 
        assertThat("Folder ownership helper should return the inherited value",
                JobOwnerHelper.Instance.getOwnershipDescription(project), 
                equalTo(original));
          
        // Disable the inheritance
        OwnershipPluginConfigurer.forJenkinsRule(j)
                .withInheritanceOptions(new InheritanceOptions(true))
                .configure();
        
        // Ensure that Ownership is disabled for both nested job and folder
        OwnershipInfo projectOwnershipInfo = JobOwnerHelper.Instance.getOwnershipInfo(
                j.jenkins.getItemByFullName("folder1/folder2/projectInFolder", FreeStyleProject.class));
        OwnershipInfo folderOwnershipInfo = FolderOwnershipHelper.getInstance().getOwnershipInfo(
                j.jenkins.getItemByFullName("folder1/folder2", Folder.class));
        assertThat("Folder should not inherit the ownership info when inheritance is disabled",
                folderOwnershipInfo.getDescription(), equalTo(OwnershipDescription.DISABLED_DESCR));
        assertThat("Project should not inherit the ownerhip info when inheritance is disabled",
                projectOwnershipInfo.getDescription(), equalTo(OwnershipDescription.DISABLED_DESCR));
    }
    
    
}
