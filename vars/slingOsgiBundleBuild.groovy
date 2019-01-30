def call(Map params = [:]) {

    def availableJDKs = [ 8: 'JDK 1.8 (latest)', 9: 'JDK 1.9 (latest)', 10: 'JDK 10 (latest)', 11: 'JDK 11 (latest)' ]
    def mvnVersion = 'Maven (latest)'
    def mainNodeLabel = 'ubuntu'
    // defaults for the build
    def jobConfig = [
        jdks: [8],
        upstreamProjects: [],
        archivePatterns: [],
        mavenGoal: '',
        additionalMavenParams: '',
        rebuildFrequency: '@weekly',
        enabled: true,
        emailRecipients: []
    ]

    def upstreamProjectsCsv = jobConfig.upstreamProjects ? 
        jsonArrayToCsv(jobConfig.upstreamProjects) : ''

    node(mainNodeLabel) {

        try {
            checkout scm

            stage('Init') {
                if ( fileExists('.sling-module.json') ) {
                    overrides = readJSON file: '.sling-module.json'
                    echo "Jenkins overrides: ${overrides.jenkins}"
                    overrides.jenkins.each { key,value ->
                        jobConfig[key] = value;
                    }
                }
                echo "Final job config: ${jobConfig}"
            }

            def jobTriggers = []
            if ( env.BRANCH_NAME == 'master' )
                jobTriggers.add(cron(jobConfig.rebuildFrequency))
            if ( upstreamProjectsCsv )
                jobTriggers.add(upstream(upstreamProjects: upstreamProjectsCsv, threshold: hudson.model.Result.SUCCESS))

            properties([
                pipelineTriggers(jobTriggers)
            ])

            if ( jobConfig.enabled ) {
                // the reference build is always the first one, and the only one to deploy, archive artifacts, etc
                // usually this is the build done with the oldest JDK version, to ensure maximum compatibility
                def isReferenceStage = true

                jobConfig.jdks.each { jdkVersion -> 
                    stageDefinition = defineStage(jobConfig, jdkVersion, isReferenceStage)
                    stageDefinition.call()
                    isReferenceStage = false
                    currentBuild.result = "SUCCESS"
                }
            } else {
                echo "Job is disabled, not building"
            }
        // exception handling copied from https://github.com/apache/maven-jenkins-lib/blob/d6c76aaea9df19ad88439eba4f9d1ad6c9e272bd/vars/asfMavenTlpPlgnBuild.groovy
        } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
            // this ambiguous condition means a user probably aborted
            if (e.causes.size() == 0) {
                currentBuild.result = "ABORTED"
            } else {
                currentBuild.result = "FAILURE"
            }
            throw e
        } catch (hudson.AbortException e) {
            // this ambiguous condition means during a shell step, user probably aborted
            if (e.getMessage().contains('script returned exit code 143')) {
                currentBuild.result = "ABORTED"
            } else {
                currentBuild.result = "FAILURE"
            }
            throw e
        } catch (InterruptedException e) {
            currentBuild.result = "ABORTED"
            throw e
        } catch (Throwable e) {
            currentBuild.result = "FAILURE"
            throw e
        } finally {
            stage("Notifications") {
                processResult(currentBuild, currentBuild.getPreviousBuild()?.result, jobConfig['emailRecipients'])
            }
        }
    }
}

def processResult(def currentBuild, String previous, def recipients) {

    if ( env.BRANCH_NAME != 'master' ) {
        echo "Not sending notifications on branch name ${env.BRANCH_NAME} != 'master'"
        return
    }

    if ( !recipients ) {
        echo "No recipients defined, not sending notifications."
        return
    }

    String current = currentBuild.result

    // values described at https://javadoc.jenkins-ci.org/hudson/model/Result.html
    // Note that we don't handle consecutive failures to prevent mail spamming

    def change = null;
    def recipientProviders = []

    // 1. changes from success or unknown to non-success
    if ( (previous == null || previous == "SUCCESS") && current != "SUCCESS" ) {
        change = "BROKEN"
        recipientProviders = [[$class: 'CulpritsRecipientProvider']]
    }

    // 2. changes from non-success to success
    if ( (previous != null && previous != "SUCCESS") && current == "SUCCESS" )
        change = "FIXED"

    if ( change == null ) {
        echo "No change in status, not sending notifications."
        return
    }
    
    echo "Status change is ${change}, notifications will be sent."

    def subject = "[Jenkins] ${currentBuild.fullDisplayName} is ${change}"
    def body = """Please see ${currentBuild.absoluteUrl} for details.

No further emails will be sent until the status of the build is changed.
    """

    if ( change == "BROKEN") {
        body += "Build log follows below:\n\n"
        body += '${BUILD_LOG}'
    }

    emailext subject: subject, body: body, replyTo: 'dev@sling.apache.org', recipientProviders: recipientProviders, to: jsonArrayToCsv(recipients)
}

// workaround for "Scripts not permitted to use method net.sf.json.JSONArray join java.lang.String"
def jsonArrayToCsv(net.sf.json.JSONArray items) {
    def result = []
    items.each { item ->
        result.add(item)
    }
    return result.join(',')
}

def defineStage(def jobConfig, def jdkVersion, def isReferenceStage) {

    def goal = jobConfig.mavenGoal ? jobConfig.mavenGoal : ( isReferenceStage ? "deploy" : "verify" )
    def branchConfig = jobConfig?.branches?."$env.BRANCH_NAME"
    def additionalMavenParams = ${branchConfig.additionalMavenParams} ?
        ${branchConfig.additionalMavenParams} : jobConfig.additionalMavenParams
    if ( branchConfig.nodeLabel && branchConfig.nodeLabel != mainNodeLabel )
        echo "Should run on nodes with label ${branchConfig.nodeLabel}, but not implemented for now"

    return {
        stage("Build (Java ${jdkVersion}, ${goal})") {
            def jenkinsJdkLabel = availableJDKs[jdkVersion]
            if ( !jenkinsJdkLabel )
                throw new RuntimeException("Unknown JDK version ${jdkVersion}")
            withMaven(maven: mvnVersion, jdk: jenkinsJdkLabel,
                options: [
                    artifactsPublisher(disabled: true),
                    junitPublisher(disabled: !isReferenceStage),
                    openTasksPublisher(disabled: !isReferenceStage),
                    dependenciesFingerprintPublisher(disabled: !isReferenceStage)
                ] ) {

                sh "mvn -U clean ${goal} ${additionalMavenParams}"
            }
            if ( isReferenceStage && jobConfig.archivePatterns ) {
                archiveArtifacts(artifacts: jsonArrayToCsv(jobConfig.archivePatterns), allowEmptyArchive: true)
            }
        }
    }
}