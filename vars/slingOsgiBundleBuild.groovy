def call(Map params = [:]) {
    def availableJDKs = [ 8: 'JDK 1.8 (latest)', 9: 'JDK 1.9 (latest)']
    def mvnVersion = 'Maven 3.3.9'
    // defaults for the build
    def buildDesc = [
        jdks: [8],
        downstreamProjects: [],
        archivePatterns: [],
        mavenGoal: '',
        additionalMavenParams: '',
        rebuildFrequency: '@weekly',
        enableXvfb: false,
        enabled: true
    ]

    def moduleDir = params.containsKey('moduleDir') ? params.moduleDir : '.'

    node('ubuntu') {
        properties([
            pipelineTriggers([
                cron(env.BRANCH_NAME == 'master' ? '@weekly' : '')
            ])
        ])

        checkout scm

        stage('Init') {
            if ( fileExists('.sling-module.json') ) {
                overrides = readJSON file: '.sling-module.json'
                echo "Jenkins overrides: ${overrides.jenkins}"
                if ( overrides.jenkins?.jdks ) {
                    buildDesc.jdks = overrides.jenkins.jdks
                }
            }
        }

        deploy = true
        buildDesc.jdks.each { jdkVersion -> 
            def goal = buildDesc.mavenGoal ? buildDesc.mavenGoal : ( deploy ? "deploy" : "verify" )
            stage("Build (Java ${jdkVersion}, ${goal})") {
                def jenkinsJdkLabel = availableJDKs[jdkVersion]
                if ( !jenkinsJdkLabel )
                    throw new RuntimeException("Unknown JDK version ${jdkVersion}")
                withMaven(maven: mvnVersion, jdk: jenkinsJdkLabel ) {
                   dir(moduleDir) {
                        sh "mvn clean ${goal}"
                    }
                }
            }
            deploy = false
        }
    }
}