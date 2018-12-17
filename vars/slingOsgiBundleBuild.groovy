def call(Map params = [:]) {
    def moduleDir = params.containsKey('moduleDir') ? params.moduleDir : '.'

    node('ubuntu') {

        checkout scm

        stage('Init') {
            def overrides = readFile('.sling-module.xml')
            echo "Got overrides ${overrides}"
        }

        stage('Build') {
            withMaven(maven: 'Maven 3.3.9', jdk: 'JDK 1.8 (latest)' ) {
                dir(moduleDir) {
                    sh 'mvn clean install' 
                }                
            }
        }
    }
}