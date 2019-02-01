import org.apache.sling.jenkins.SlingJenkinsHelper;

def call(Map params = [:]) {

    def globalConfig = [
        availableJDKs : [ 8: 'JDK 1.8 (latest)', 9: 'JDK 1.9 (latest)', 10: 'JDK 10 (latest)', 11: 'JDK 11 (latest)' ],
        mvnVersion : 'Maven (latest)',
        mainNodeLabel : 'ubuntu',
        githubCredentialsId: 'sling-github-token'
    ]

    node(globalConfig.mainNodeLabel) {

        def helper = new SlingJenkinsHelper()

        helper.runWithErrorHandling({ jobConfig ->
            if ( jobConfig.enabled ) {
                // the reference build is always the first one, and the only one to deploy, archive artifacts, etc
                // usually this is the build done with the oldest JDK version, to ensure maximum compatibility
                def isReferenceStage = true

                jobConfig.jdks.each { jdkVersion -> 
                    stageDefinition = defineStage(globalConfig, jobConfig, jdkVersion, isReferenceStage)
                    stageDefinition.call()
                    isReferenceStage = false
                    currentBuild.result = "SUCCESS"
                }

                // this might fail if there are no jdks defined, but that's always an error
                // also, we don't activate any Maven publisher since we don't want this part of the
                // build tracked, but using withMaven(...) allows us to easily reuse the same
                // Maven and JDK versions
//                 if ( env.BRANCH_NAME == "master" ) {
                    def additionalMavenParams = additionalMavenParams(jobConfig)

                    // debugging for Sonar
                    if ( env.BRANCH_NAME.startsWith("PR-") ) {
                        def repo = getGitHubRepoSlug()
                        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: globalConfig.githubCredentialsId, usernameVariable: 'USERNAME', passwordVariable: 'TOKEN']]) {
                            additionalMavenParams="${additionalMavenParams} -Dsonar.analysis.mode=preview -Dsonar.github.repository=${repo} -Dsonar.github.pullRequest=${env.CHANGE_ID} -Dsonar.github.login=${USERNAME} -Dsonar.verbose=true -Dsonar.issuesReport.console.enable=true"
                        }
                    }
                    stage('SonarQube') {
                        withSonarQubeEnv('ASF Sonar Analysis') {
                            withMaven(maven: globalConfig.mvnVersion, 
                                jdk: jenkinsJdkLabel(jobConfig.jdks[0], globalConfig),
                                publisherStrategy: 'EXPLICIT') {
                                sh "mvn -U clean verify sonar:sonar ${additionalMavenParams}"
                            }
                        }
                    }
  //              }
            } else {
                echo "Job is disabled, not building"
            }
        })
    }
}

def jenkinsJdkLabel(int jdkVersion, def globalConfig) {
    def label = globalConfig.availableJDKs[jdkVersion]
    if ( !label )
        throw new RuntimeException("Unknown JDK version ${jdkVersion}")    
    return label
}

def additionalMavenParams(def jobConfig) {
    def branchConfig = jobConfig?.branches?."$env.BRANCH_NAME" ?: [:]
    return branchConfig.additionalMavenParams ?
        branchConfig.additionalMavenParams : jobConfig.additionalMavenParams
}

@NonCPS
def getGitHubRepoSlug() {
    if ( !env.CHANGE_URL )
        return null
    
    def matcher = env.CHANGE_URL =~ /https:\/\/github\.com\/([\w-]+)\/([\w-]+)\/pull\/\d+/
    if ( !matcher )
        return null

    return "${matcher.group(1)}/${matcher.group(2)}"
}

def defineStage(def globalConfig, def jobConfig, def jdkVersion, def isReferenceStage) {

    def goal = jobConfig.mavenGoal ? jobConfig.mavenGoal : ( isReferenceStage ? "deploy" : "verify" )
    def additionalMavenParams = additionalMavenParams(jobConfig)
    def jenkinsJdkLabel = jenkinsJdkLabel(jdkVersion, globalConfig)

    // do not deploy artifacts built from PRs or feature branches
    if ( goal == "deploy" && env.BRANCH_NAME != "master" )
        goal = "verify"

    def invocation = {
        withMaven(maven: globalConfig.mvnVersion, jdk: jenkinsJdkLabel,
            options: [
                artifactsPublisher(disabled: !isReferenceStage),
                junitPublisher(disabled: !isReferenceStage),
                openTasksPublisher(disabled: !isReferenceStage),
                dependenciesFingerprintPublisher(disabled: !isReferenceStage)
            ] ) {

            sh "mvn -U clean ${goal} ${additionalMavenParams}"
        }
        if ( isReferenceStage && jobConfig.archivePatterns ) {
            archiveArtifacts(artifacts: SlingJenkinsHelper.jsonArrayToCsv(jobConfig.archivePatterns), allowEmptyArchive: true)
        }
    }
    
    def branchConfig = jobConfig?.branches?."$env.BRANCH_NAME" ?: [:]
    if ( branchConfig.nodeLabel && branchConfig.nodeLabel != globalConfig.mainNodeLabel )
        invocation = wrapInNode(invocation,branchConfig.nodeLabel)


    return {
        stage("Build (Java ${jdkVersion}, ${goal})") {
            invocation.call()
        }
    }
}

def wrapInNode(Closure invocation, def nodeLabel) {
    return {
        node(nodeLabel) {
            checkout scm
            invocation.call()
        }
    }
}