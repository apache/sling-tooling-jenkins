def call(Map params = [:]) {
    def moduleDir = params.containsKey('moduleDir') ? params.moduleDir : '.'

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
                    junit '**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml'
                }
            }
        }
    }
}