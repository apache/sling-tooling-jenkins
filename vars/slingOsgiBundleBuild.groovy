def call(Map params = [:]) {
    def availableJDKs = [ 8: 'JDK 1.8 (latest)', 9: 'JDK 1.9 (latest)']
    def mvnVersion = 'Maven 3.3.9'
    // defaults for the build
    def buildDesc = {
        jdks: [8],
        downstreamProjects: [],
        archivePatterns: [],
        mavenGoal: 'install',
        additionalMavenParams: '',
        rebuildFrequency: '@weekly',
        enableXvfb: false,
        enabled: true
    }

    def moduleDir = params.containsKey('moduleDir') ? params.moduleDir : '.'

    node('ubuntu') {

        checkout scm

        stage('Init') {
            if ( fileExists('.sling-module.xml') ) {
                def overrides = readFile('.sling-module.xml')
                def slingMod = new XmlParser().parse(overrides)
                if ( slingMod?.jenkins?.jdks ) {
                    def jdks = []
                    slingMod.jenkins.jdks.jdk.each { jdks.add it.text() }
                    buildDesc.jdks = jdks

                    echo "$Overriding JDKs list to be ${jdks}"
                }
            }
        }

        buildDesc.jdks.each { jdkVersion -> 
            stage("Build (Java ${jdkVersion})") {
                def jenkinsJdkLabel = availableJDKs[jdkVersion]
                if ( !jenkinsJdkLabel )
                    throw new RuntimeException("Unknown JDK version ${jdkVersion}")
                withMaven(maven: mvnVersion, jdk: jenkinsJdkLabel ) {
                   dir(moduleDir) {
                        sh 'mvn clean install' 
                    }
                }
            }
        }
    }
}