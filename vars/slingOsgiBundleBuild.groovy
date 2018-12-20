def call(Map params = [:]) {

    def availableJDKs = [ 8: 'JDK 1.8 (latest)', 9: 'JDK 1.9 (latest)', 10: 'JDK 10 (latest)', 11: 'JDK 11 (latest)' ]
    def mvnVersion = 'Maven (latest)'
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

    def moduleDir = params.containsKey('moduleDir') ? params.moduleDir : '.'
    def upstreamProjectsCsv = jobConfig.upstreamProjects ? 
        jobConfig.upstreamProjects.join(',') : ''

    node('ubuntu') {

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

            def jobTriggers = [
                pollSCM('* * * * *')
            ]
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
                def reference = true
                jobConfig.jdks.each { jdkVersion -> 
                    def goal = jobConfig.mavenGoal ? jobConfig.mavenGoal : ( reference ? "deploy" : "verify" )
                    stage("Build (Java ${jdkVersion}, ${goal})") {
                        def jenkinsJdkLabel = availableJDKs[jdkVersion]
                        if ( !jenkinsJdkLabel )
                            throw new RuntimeException("Unknown JDK version ${jdkVersion}")
                        withMaven(maven: mvnVersion, jdk: jenkinsJdkLabel, 
                            options: [
                                artifactsPublisher(disabled: true),
                                junitPublisher(disabled: !reference),
                                openTasksPublisher(disabled: !reference),
                                dependenciesFingerprintPublisher(disabled: !reference)
                            ] ) {
                        dir(moduleDir) {
                                sh "mvn -U clean ${goal} ${jobConfig.additionalMavenParams}"
                            }
                        }
                        if ( reference && jobConfig.archivePatterns ) {
                            archiveArtifacts(artifacts: jobConfig.archivePatterns.join(','), allowEmptyArchive: true)
                        }
                    }
                    reference = false
                }
            } else {
                echo "Job is disabled, not building"
            }
        } finally {
            processResult(currentBuild, currentBuild.getPreviousResult()?.result)
        }
    }
}

def processResult(def currentBuild, String previous) {

    String current = currentBuild.result

    // values described at https://javadoc.jenkins-ci.org/hudson/model/Result.html
    // Note that we don't handle consecutive failures to prevent mail spamming

    echo "Result status : ${current}, previous: ${previous}"

    // 1. changes from success or unknown to non-success
    if ( (previous == null || previous == "SUCCESS") && current != "SUCCESS" )
        echo "Regression"

    // 2. changes from non-success to success
    if ( (previous != null && previous != "SUCCESS") && current == "SUCCESS" )
        echo "Fixed"
}