def call(Map params = [:]) {
    def moduleDir = params.containsKey('moduleDir') ? params.moduleDir : '.'
    
    def overrides = readFile('.sling-module.xml')
    echo "Got overrides ${overrides}"
    def missingFile = readFile("blah")
    echo "Got missing file ${missingFile}"

    pipeline {
        agent {
            label 'ubuntu'
        }

        tools {
            maven 'Maven 3.3.9'
            jdk 'JDK 1.8 (latest)'
        }

        stages {
            stage ('Build') {
                steps {
                    dir(moduleDir) {
                        sh 'mvn clean install' 
                    }
                }
            }
        }

        post {
            always {
                dir(moduleDir) {
                    junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml'
                }
            }
        }
    }
}