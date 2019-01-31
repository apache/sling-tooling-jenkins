import org.apache.sling.jenkins.SlingJenkinsHelper;

def call(Map params = [:]) {

    def globalConfig = [
        availableJDKs : [ 8: 'JDK 1.8 (latest)', 9: 'JDK 1.9 (latest)', 10: 'JDK 10 (latest)', 11: 'JDK 11 (latest)' ],
        mvnVersion : 'Maven (latest)',
        mainNodeLabel : 'ubuntu'
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
            } else {
                echo "Job is disabled, not building"
            }
        })
    }
}

def defineStage(def globalConfig, def jobConfig, def jdkVersion, def isReferenceStage) {

    def goal = jobConfig.mavenGoal ? jobConfig.mavenGoal : ( isReferenceStage ? "deploy" : "verify" )
    def branchConfig = jobConfig?.branches?."$env.BRANCH_NAME" ?: [:]
    def additionalMavenParams = branchConfig.additionalMavenParams ?
        branchConfig.additionalMavenParams : jobConfig.additionalMavenParams
    def jenkinsJdkLabel = globalConfig.availableJDKs[jdkVersion]
    if ( !jenkinsJdkLabel )
        throw new RuntimeException("Unknown JDK version ${jdkVersion}")

    // do not deploy artifacts built from PRs or feature branches
    if ( goal == "deploy" && env.BRANCH_NAME != "master" )
        goal = "verify"

    def invocation = {
        withMaven(maven: globalConfig.mvnVersion, jdk: jenkinsJdkLabel,
            options: [
                artifactsPublisher(disabled: true),
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