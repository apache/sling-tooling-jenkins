import org.apache.sling.jenkins.SlingJenkinsHelper;

def call(Map params = [:]) {

    def globalConfig = [
        availableJDKs : [ 8: 'jdk_1.8_latest', 9: 'jdk_1.9_latest', 10: 'jdk_10_latest', 11: 'jdk_11_latest', 12: 'jdk_12_latest', 13: 'jdk_13_latest', 14: 'jdk_14_latest', 15: 'jdk_15_latest', 16: 'jdk_16_latest', 17: 'jdk_17_latest', 18: 'jdk_18_latest'],
        mvnVersion : 'maven_3_latest',
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
                def additionalMavenParams = additionalMavenParams(jobConfig)
                def isPrBuild = env.BRANCH_NAME.startsWith("PR-")

                if ( jobConfig.sonarQubeEnabled ) {
                    stage('SonarCloud') {
                        // As we don't have the global SonarCloud conf for now, we can't use #withSonarQubeEnv so we need to set the following props manually
                        def sonarcloudParams="-Dsonar.host.url=https://sonarcloud.io -Dsonar.organization=apache -Dsonar.projectKey=apache_${jobConfig.repoName} -Pjacoco-report -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco-merged/jacoco.xml ${jobConfig.sonarQubeAdditionalParams}"
                        if ( jobConfig.sonarQubeUseAdditionalMavenParams ) {
                            sonarcloudParams="${sonarcloudParams} ${additionalMavenParams}"
                        }
                        // Params are different if it's a PR or if it's not
                        // Note: soon we won't have to handle that manually, see https://jira.sonarsource.com/browse/SONAR-11853
                        if ( isPrBuild ) {
                            sonarcloudParams="${sonarcloudParams} -Dsonar.pullrequest.branch=${CHANGE_BRANCH} -Dsonar.pullrequest.base=${CHANGE_TARGET} -Dsonar.pullrequest.key=${CHANGE_ID}"
                        } else if ( env.BRANCH_NAME != "master" ) {
                            sonarcloudParams="${sonarcloudParams} -Dsonar.branch.name=${BRANCH_NAME}"
                        }
                        // Alls params are set, let's execute using #withCrendentials to hide and mask Robert's token
                        withCredentials([string(credentialsId: 'sonarcloud-token-rombert', variable: 'SONAR_TOKEN')]) {
                            // always build with Java 11 (that is the minimum version supported: https://sonarcloud.io/documentation/appendices/end-of-support/)
                            withMaven(maven: globalConfig.mvnVersion, 
                                jdk: jenkinsJdkLabel(11, globalConfig),
                                publisherStrategy: 'EXPLICIT') {
                                    try {
                                         sh  "mvn -U clean verify sonar:sonar ${sonarcloudParams} -Pci"
                                    } catch ( Exception e ) {
                                        // TODO - we should check the actual failure cause here, but see
                                        // https://stackoverflow.com/questions/55742773/get-the-cause-of-a-maven-build-failure-inside-a-jenkins-pipeline/55744122
                                        echo "Marking build unstable due to mvn sonar:sonar failing. See https://cwiki.apache.org/confluence/display/SLING/SonarCloud+analysis for more info."
                                        currentBuild.result = 'UNSTABLE'
                                    }
                            }
                        }
                    }
                } else {
                    echo "SonarQube execution is disabled"
                }
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

def defineStage(def globalConfig, def jobConfig, def jdkVersion, def isReferenceStage) {

    def goal = jobConfig.mavenGoal ? jobConfig.mavenGoal : ( isReferenceStage ? "deploy" : "verify" )
    def additionalMavenParams = additionalMavenParams(jobConfig)
    def jenkinsJdkLabel = jenkinsJdkLabel(jdkVersion, globalConfig)

    // do not deploy artifacts built from PRs or feature branches
    // also do not deploy non-SNAPSHOT versions
    if ( goal == "deploy" ) {
        def notMaster =  env.BRANCH_NAME != "master"
        def mavenPom = readMavenPom()
        def mavenVersion = mavenPom.version ?: mavenPom.parent.version
        def isSnapshot = mavenVersion.endsWith('-SNAPSHOT')
        if ( notMaster || !isSnapshot ) {
            goal = "verify"
            echo "Maven goal set to ${goal} since branch is not master ( ${env.BRANCH_NAME} ) or version is not snapshot ( ${mavenVersion} )"
        }            
    }

    def invocation = {
        withMaven(maven: globalConfig.mvnVersion, jdk: jenkinsJdkLabel,
            options: [
                artifactsPublisher(disabled: !isReferenceStage),
                junitPublisher(disabled: !isReferenceStage),
                openTasksPublisher(disabled: !isReferenceStage),
                dependenciesFingerprintPublisher(disabled: !isReferenceStage)
            ] ) {

            sh "mvn -U clean ${goal} ${additionalMavenParams} -Dci"
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
