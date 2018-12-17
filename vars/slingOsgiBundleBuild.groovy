import sling.SlingModuleParser;

def call(Map params = [:]) {
    def availableJDKs = [ 8: 'JDK 1.8 (latest)', 9: 'JDK 1.9 (latest)']
    def mvnVersion = 'Maven 3.3.9'

    def moduleDir = params.containsKey('moduleDir') ? params.moduleDir : '.'

    node('ubuntu') {

        checkout scm

        stage('Init') {
            def parser = new SlingModuleParser('.sling-module.xml')
            if ( fileExists('.sling-module.xml') ) {
                parser.parse()
            }
            def buildDesc = parser.buildDesc;
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