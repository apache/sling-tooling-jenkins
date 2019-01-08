def rawUrlPrefix = "https://raw.githubusercontent.com/apache"
def repoUrl = rawUrlPrefix + "/sling-aggregator/master/default.xml"
def repoBase = "https://github.com/apache/"
// Repositories are read from the repo definition file. Customizations must be
// placed in a .sling-module.xml file in the git repository root. See
// https://cwiki.apache.org/confluence/display/SLING/Sling+module+descriptor 
// for a description of the format
// This is a Jenkins DSL script, I don't think you can run it standalone.
// See that wiki page for how to setup a local Jenkins test instance.
def modules = []

def manifest = new XmlParser().parse(repoUrl)
manifest.project.each { project ->
    jobName = project.@name.toString().replace(".git","")
    def slingMod = []
    def createJob = true
    try {
        slingMod = new XmlParser().parse(rawUrlPrefix + "/" + jobName + "/master/.sling-module.xml")
        println "${jobName}: found custom .sling-module.xml"
    } catch ( FileNotFoundException e) {
        println "${jobName}: no .sling-module.xml found, using defaults"
    }

    def module = [ location: jobName ]

    def connection = new URL(rawUrlPrefix + "/" + jobName + "/master/Jenkinsfile").openConnection()
    module.pipeline = connection.responseCode == 200
    if ( module.pipeline ) {
        println "${jobName}: Jenkinsfile found, creating pipeline job"
    }

    if ( slingMod?.jenkins?.jdks ) {
        def jdks = []
        slingMod.jenkins.jdks.jdk.each { jdks.add it.text() }
        module.jdks = jdks

        println "${jobName}: overriding JDKs list to be ${jdks}"
    }

    if ( slingMod?.jenkins?.enabled ) {
        createJob = Boolean.valueOf(slingMod.jenkins.enabled.text())
        println "${jobName}: overriding job creation with value ${createJob}"
    }

    if ( slingMod?.jenkins?.enableXvfb ) {
        module.enableXvfb = Boolean.valueOf(slingMod.jenkins.enableXvfb.text())
        println "${jobName}: overriding xvfb support with value ${module.enableXvfb}"
    }

    if ( slingMod?.jenkins?.mavenGoal ) {
        module.mavenGoal = slingMod.jenkins.mavenGoal.text()
        println "${jobName}: overriding default maven goal with value ${module.mavenGoal}"
    }

    if ( slingMod?.jenkins?.additionalMavenParams ) {
        module.extraGoalsParams = slingMod.jenkins.additionalMavenParams.text()
        println "${jobName}: overriding additional maven parameters with value ${module.extraGoalsParams}"
    }

    if ( slingMod?.jenkins?.rebuildFrequency ) {
        module.rebuildFrequency = slingMod.jenkins.rebuildFrequency.text()
        println "${jobName}: overriding default rebuild frequency with value ${module.rebuildFrequency}"
    }

    if ( slingMod?.jenkins?.archivePatterns ) {
        module.archivePatterns = []
        slingMod.jenkins.archivePatterns.archivePattern.each { module.archivePatterns.add it.text() }
        println "${jobName}: overriding archive patterns to be ${module.archivePatterns}"
    }

    if ( slingMod?.jenkins?.downstreamProjects ) {
        module.downstreamProjects = []
        slingMod.jenkins.downstreamProjects.downstreamProject.each { module.downstreamProjects.add it.text() }
        println "${jobName}: overriding downstream projects to be ${module.downstreamProjects}"
    }

    if ( createJob ) {
        modules += module
    }
}

// should be sorted from the oldest to the latest version
// so that artifacts built using the oldest version are
// deployed for maximum compatibility
def defaultJdks = ["1.8"]
def defaultMvn = "Maven (latest)"
def defaultSlave = "ubuntu"

def jdkMapping = [
    "1.7": "JDK 1.7 (latest)",
    "1.8": "JDK 1.8 (latest)",
    "9"  : "JDK 1.9 (latest)",
    "10" : "JDK 10 (latest)",
    "11" : "JDK 11 (latest)"

]

modules.each { module ->

    if ( module.pipeline ) {
        multibranchPipelineJob(module.location) {
            description('''
    <p>This build was automatically generated and any manual edits will be lost.</p>
    <p>See <a href="https://cwiki.apache.org/confluence/display/SLING/Sling+Jenkins+Setup">Sling Jenkins Setup</a>
    for more details</p>''')
            branchSources {
                github {
                    scanCredentialsId('sling-github-token') // needs repo:status scope
                    repoOwner('apache')
                    repository(module.location)
                }
            }

            orphanedItemStrategy {
                discardOldItems {
                    numToKeep(15)
                }
            }

            triggers {
                periodic(1)
            }            
        }
    } else {
        def jdks = module.jdks ?: defaultJdks
        def deploy = true

        def downstreamProjects = module.downstream?: []
        def downstreamEntries = modules.findAll { downstreamProjects.contains(it.location) }
        def downstreamJobs = []

        downstreamEntries.each { downstreamEntry ->
            def downstreamJdks = downstreamEntry.jdks?: defaultJdks
            def downstreamLocation = downstreamEntry.location
            downstreamJdks.each { downstreamJdk ->
                downstreamJobs.add(jobName(downstreamLocation,downstreamJdk))
            }
        }

        jdks.each { jdkKey ->
            mavenJob(jobName(module.location, jdkKey)) {

                description('''
    <p>This build was automatically generated and any manual edits will be lost.</p>
    <p>See <a href="https://cwiki.apache.org/confluence/display/SLING/Sling+Jenkins+Setup">Sling Jenkins Setup</a>
    for more details</p>''')

                logRotator {
                    numToKeep(15)
                }

                scm {
                    git {
                        remote {
                            github('apache/' + module.location)
                        }
                        branches('master')
                    }
                }

                blockOnUpstreamProjects()

                triggers {
                    snapshotDependencies(true)
                    scm('H/15 * * * *')
                    def rebuildFrequency = module.rebuildFrequency ? module.rebuildFrequency : '@weekly'
                    cron(rebuildFrequency)
                }

                // timeout if the job takes 4 times longer than the average
                // duration of the last 3 jobs. Defaults to 30 minutes if
                // no previous job executions are found
                wrappers {
                    timeout {
                        elastic(400, 3, 30)
                    }

                    if ( module.enableXvfb ) {
                        xvfb('Xvfb')
                    }

                    // INFRA-17090
                    preBuildCleanup()
                }

                blockOnUpstreamProjects()

                jdk(jdkMapping.get(jdkKey))

                mavenInstallation(defaultMvn)

                // we have no use for archived artifacts since they are deployed on
                // repository.apache.org so speed up the build a bit (and probably
                // save on disk space)
                archivingDisabled(true)

                label(defaultSlave)

                // ensure that only one job deploys artifacts
                // besides being less efficient, it's not sure which
                // job is triggered first and we may end up with a
                // mix of Java 7 and Java 8 artifacts for projects which
                // use these 2 versions
                def extraGoalsParams = module.extraGoalsParams ?: ""
                def goal = module.mavenGoal ? module.mavenGoal : ( deploy ? "deploy" : "verify" )
                goals( "-U clean ${goal} ${extraGoalsParams}")

                publishers {
                    if ( deploy && downstreamJobs ) {
                        downstream(downstreamJobs)
                    }

                    if (module.archivePatterns) {
                        archiveArtifacts() {
                            module.archivePatterns.each { archiveEntry ->
                                pattern(archiveEntry)
                            }
                        }
                    }

                    // TODO - can we remove the glob and rely on the defaults?
                    archiveJunit('**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml') {
                        allowEmptyResults()
                    }
                    // send emails for each broken build, notify individuals as well
                    mailer('commits@sling.apache.org', false, true)
                }

                deploy = false
            }
        }
    }
}

String jobName(String location, String jdk) {
    return location + '-' + jdk;
}
