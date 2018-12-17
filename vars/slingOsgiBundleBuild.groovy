def call(Map params = [:]) {

    def availableJDKs = [ 8: 'JDK 1.8 (latest)', 9: 'JDK 1.9 (latest)', 10: 'JDK 10 (latest)', 11: 'JDK 11 (latest)' ]
    def mvnVersion = 'Maven 3.3.9'
    // defaults for the build
    def jobConfig = [
        jdks: [8],
        upstreamProjects: [],
        archivePatterns: [],
        mavenGoal: '',
        additionalMavenParams: '',
        rebuildFrequency: '@weekly',
        enableXvfb: false,
        enabled: true
    ]

    def moduleDir = params.containsKey('moduleDir') ? params.moduleDir : '.'
    def upstreamProjectsCsv = upstreamProjects ? 
        upstreamProjects.join(',') : ''

    node('ubuntu') {
        properties([
            pipelineTriggers([
                cron(env.BRANCH_NAME == 'master' ? '@weekly' : '')
                pollSCM('* * * * *')
                upstream(upstreamProjects: upstreamProjectsCsv, threshold: hudson.model.Result.SUCCESS)

            ])
        ])

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

        if ( jobConfig.enabled ) {
            deploy = true
            jobConfig.jdks.each { jdkVersion -> 
                def goal = jobConfig.mavenGoal ? jobConfig.mavenGoal : ( deploy ? "deploy" : "verify" )
                stage("Build (Java ${jdkVersion}, ${goal})") {
                    def jenkinsJdkLabel = availableJDKs[jdkVersion]
                    if ( !jenkinsJdkLabel )
                        throw new RuntimeException("Unknown JDK version ${jdkVersion}")
                    withMaven(maven: mvnVersion, jdk: jenkinsJdkLabel, artifactsPublisher(disabled: true) ) {
                    dir(moduleDir) {
                            sh "mvn clean ${goal} ${jobConfig.additionalMavenParams}"
                        }
                    }
                    if ( jobConfig.archivePatterns ) {
                        archiveArtifacts(artifacts: jobConfig.archivePatterns.join(','), allowEmptyArchive: true)
                    }
                }
                deploy = false
            }
        } else {
            echo "Job is disabled, not building"
        }
    }
}