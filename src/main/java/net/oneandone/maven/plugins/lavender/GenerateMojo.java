/*
 * Copyright 1&1 Internet AG, https://github.com/1and1/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.oneandone.maven.plugins.lavender;

import net.oneandone.sushi.fs.Node;
import net.oneandone.sushi.fs.World;
import net.oneandone.sushi.fs.file.FileNode;
import net.oneandone.sushi.fs.filter.Filter;
import net.oneandone.sushi.util.Separator;
import org.apache.maven.model.Scm;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.CommandParameters;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.command.info.InfoScmResult;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.repository.ScmRepository;

import java.io.IOException;
import java.util.Properties;

/** Generates Lavender Properties */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public class GenerateMojo extends AbstractMojo {
    private final World world;

    /**
     * Directory where to place the Launch Script and the executable Jar file.
     * Usually, there's no need to change the default value, which is target.
     */
    @Parameter(defaultValue = "${project.build.directory}")
    private FileNode buildDirectory;

    @Parameter(name = "includes", defaultValue = "", property = "lavender.includes")
    private String includes = "";

    @Parameter(name = "excludes", defaultValue = "htdocs/**/*", property = "lavender.excludes")
    private String excludes = "";

    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    @Component
    private ScmManager scmManager;

    public GenerateMojo() throws IOException {
        this.world = World.create();
    }

    public void execute() throws MojoExecutionException {
        try {
            checkSourceProperties();
            doExecute();
        } catch (IOException e) {
            throw new MojoExecutionException("cannot generate application: " + e.getMessage(), e);
        }
    }

    /**
     * We used to have source lavender.properties to configure lavender -- that's no longer supported.
     * This check makes sure that it's not used. TODO: dump when we've migrated everything to Lavender 2.10
     */
    private void checkSourceProperties() throws MojoExecutionException, IOException {
        FileNode file;

        file = world.file(project.getBasedir()).join(isWebapp()
                ? "src/main/webapp/WEB-INF/lavender.properties" : "src/main/resources/META-INF/lavender.properties");
        if (file.exists()) {
            if (!file.readString().trim().isEmpty()) {
                throw new MojoExecutionException("source properties not empty: " + file);
            }
        }
    }

    private boolean isWebapp() {
        return "war".equals(project.getPackaging());
    }

    private void doExecute() throws IOException, MojoExecutionException {
        Scm scm;
        boolean webapp;
        String name;
        String path;
        FileNode dest;
        String resourcePathPrefix;
        Properties p;

        scm = project.getScm();
        webapp = isWebapp();
        getLog().info("webapp: " + webapp);
        name = webapp ? "webapp" : project.getArtifactId();
        path = webapp ? "src/main/webapp" : "src/main/resources";
        dest = (webapp ? buildDirectory.join(project.getArtifactId(), "WEB-INF") : buildDirectory.join("classes/META-INF")).join("lavender.properties");
        resourcePathPrefix = webapp ? "" : "modules/" + project.getArtifactId() + "/";
        p = new Properties();
        p.put("scm." + name, scm.getConnection());
        p.put("scm." + name + ".devel", scm.getDeveloperConnection());
        p.put("scm." + name + ".path", path);
        p.put("scm." + name + ".tag", "" + "" + getScmRevision());
        p.put("scm." + name + ".includes", includes);
        p.put("scm." + name + ".excludes", excludes);
        p.put("scm." + name + ".resourcePathPrefix", resourcePathPrefix);
        scan(path, p);
        dest.writeProperties(p, "generated by lavender-plugin");
        getLog().info("generated " + dest);
    }

    public Node getBuildDirectory() {
        return buildDirectory;
    }

    public void setBuildDirectory(String dest) {
        this.buildDirectory = world.file(dest);
    }

    public void scan(String path, Properties dest) throws IOException {
        FileNode dir;
        Filter filter;

        dir = world.file(project.getBasedir()).join(path);
        filter = world.filter();
        filter.include(Separator.COMMA.split(includes));
        filter.exclude(Separator.COMMA.split(excludes));
        for (FileNode file : dir.find(filter)) {
            dest.put("index." + file.getRelative(dir), file.md5());
        }
    }

    protected String getScmRevision() throws MojoExecutionException {
        ScmRepository repository;
        InfoScmResult result;

        try {
            repository = scmManager.makeScmRepository(project.getScm().getConnection());
            result = scmManager.getProviderByRepository(repository).info(repository.getProviderRepository(),
                    new ScmFileSet(project.getBasedir()), new CommandParameters());
        } catch (ScmException e) {
            throw new MojoExecutionException("scm operation failed: " + e.getMessage(), e);
        }
        if (result == null || result.getInfoItems().isEmpty()) {
            throw new MojoExecutionException("cannot determine scm revision");
        }
        if (!result.isSuccess()) {
            throw new MojoExecutionException("scm operation failed: " + result);
        }
        return result.getInfoItems().get(0).getRevision();
    }

}
