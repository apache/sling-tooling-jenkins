def call(Map params = [:]) {
    def availableJDKs = [ 8: 'JDK 1.8 (latest)', 9: 'JDK 1.9 (latest)']
    
    def moduleDir = params.containsKey('moduleDir') ? params.moduleDir : '.'
    def mvnVersion = 'Maven 3.3.9'
    def jdkVersions = [8, 9]

    node('ubuntu') {

        checkout scm

        stage('Init') {
            def overrides = readFile('.sling-module.xml')
            echo "Got overrides ${overrides}"
        }

        jdkVersions.each { jdkVersion -> 
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