import org.apache.sling.jenkins.SlingJenkinsHelper;

def call(Map params = [:]) {

    def globalConfig = [
        // https://cwiki.apache.org/confluence/x/kRLiAw (most are Eclipse Temurin distributions of Open JDK from Adoptium)
        availableJDKs : [ 8: 'jdk_1.8_latest', 9: 'jdk_1.9_latest', 10: 'jdk_10_latest', 11: 'jdk_11_latest',
                          12: 'jdk_12_latest', 13: 'jdk_13_latest', 14: 'jdk_14_latest', 15: 'jdk_15_latest',
                          16: 'jdk_16_latest', 17: 'jdk_17_latest', 18: 'jdk_18_latest', 19: 'jdk_19_latest', 
                          20: 'jdk_20_latest', 21: 'jdk_21_latest', 22: 'jdk_22_latest'],
        // https://cwiki.apache.org/confluence/x/cRTiAw
        mvnVersion : 'maven_3_latest',
        // maps values to node labels (available ones in https://cwiki.apache.org/confluence/x/ViZ4CQ)
        availableOperatingSystems : ['windows' : 'Windows', 'linux': 'ubuntu', 'linux-arm': 'arm', 'ubuntu': 'ubuntu'],
        mainNodeLabel : 'ubuntu',
        githubCredentialsId: 'sling-github-token'
    ]

    def jobConfig = [
        jdks: [11,17,21],
        operatingSystems: ['linux','windows'],
        upstreamProjects: [],
        archivePatterns: [],
        mavenGoal: '',
        additionalMavenParams: '',
        rebuildFrequency: '@weekly',
        enabled: true,
        emailRecipients: [],
        sonarQubeEnabled: true,
        sonarQubeUseAdditionalMavenParams: true,
        sonarQubeAdditionalParams: ''
    ]
    boolean shouldDeploy = false
    node(globalConfig.mainNodeLabel) {
        timeout(time:5, unit: 'MINUTES') {
            stage('Init') {
                checkout scm
                def url
                if (isUnix()) {
                    sh "git clean -fdx"
                    url = sh(returnStdout: true, script: 'git config remote.origin.url').trim()
                } else {
                    bat "git clean -fdx"
                    url = bat(returnStdout: true, script: 'git config remote.origin.url').trim()
                }
                jobConfig.repoName = url.substring(url.lastIndexOf('/') + 1).replace('.git', '');
                if ( fileExists('.sling-module.json') ) {
                    overrides = readJSON file: '.sling-module.json'
                    echo "Jenkins overrides: ${overrides.jenkins}"
                    overrides.jenkins.each { key,value ->
                        jobConfig[key] = value;
                    }
                }
                echo "Final job config: ${jobConfig}"
                shouldDeploy = getShouldDeploy()
            }
        }
    }

    node(globalConfig.mainNodeLabel) {
        timeout(time:30, unit: 'MINUTES', activity: true) {
            stage('Configure Job') {
                def upstreamProjectsCsv = jobConfig.upstreamProjects ?
                    jsonArrayToCsv(jobConfig.upstreamProjects) : ''
                def jobTriggers = []
                if ( isOnMainBranch() )
                    jobTriggers.add(cron(jobConfig.rebuildFrequency))
                if ( upstreamProjectsCsv )
                    jobTriggers.add(upstream(upstreamProjects: upstreamProjectsCsv, threshold: hudson.model.Result.SUCCESS))

                properties([
                    pipelineTriggers(jobTriggers),
                    buildDiscarder(logRotator(numToKeepStr: '10'))
                ])
            }
        }
    }

    if ( jobConfig.enabled ) {
        def helper = new SlingJenkinsHelper()
        helper.runWithErrorHandling(jobConfig, {
            // the reference build is always the first one, and the only one to deploy, archive artifacts, etc
            // usually this is the build done with the oldest JDK version, to ensure maximum compatibility
            boolean isReferenceStage = true

            // contains the label as key and a closure to execute as value
            def stepsMap = [failFast: true] // fail-fast, https://stackoverflow.com/a/37356318
            def referenceJdkVersion
            // parallel execution of all build jobs
            jobConfig.jdks.each { jdkVersion -> 
                jobConfig.operatingSystems.each { operatingSystem ->
                    stageDefinition = defineStage(globalConfig, jobConfig, jdkVersion, operatingSystem, isReferenceStage, shouldDeploy)
                    if ( isReferenceStage ) {
                        referenceJdkVersion = jdkVersion
                    }
                    stepsMap["Build (Java ${jdkVersion} on ${operatingSystem})"] = stageDefinition
                    isReferenceStage = false
                    currentBuild.result = "SUCCESS"
                }
            }

            // do a quick sanity check first without tests if multiple parallel builds are required
            // the stepsMap has at least one entry due to the failFast entry
            if ( stepsMap.size() > 2 ) {
                node(globalConfig.mainNodeLabel) {
                    stage("Sanity Check") {
                        checkout scm
                        withMaven(maven: globalConfig.mvnVersion,
                            jdk: jenkinsJdkLabel(referenceJdkVersion, globalConfig),
                            publisherStrategy: 'EXPLICIT') {
                                String mvnCommand = "mvn -U -B -e clean compile ${additionalMavenParams(jobConfig)}"
                                if (isUnix()) {
                                    sh mvnCommand
                                } else {
                                    bat mvnCommand
                                }
                            }
                    }
                }
            }

            // execute the actual Maven builds
            parallel stepsMap

            // last stage is deploy
            def goal = jobConfig.mavenGoal ?: "deploy"
            if ( goal == "deploy" && shouldDeploy ) {
                node(globalConfig.mainNodeLabel) {
                    stage("Deploy to Nexus") {
                        deployToNexus(globalConfig)
                    }
                }
            }
        })
    } else {
        echo "Job is disabled, not building"
    }
}

def jenkinsJdkLabel(int jdkVersion, def globalConfig) {
    def label = globalConfig.availableJDKs[jdkVersion]
    if ( !label )
        error("Unknown JDK version ${jdkVersion}. Available JDKs: ${globalConfig.availableJDKs}")
    return label
}

def jenkinsNodeLabel(String operatingSystem, def jobConfig, def globalConfig) {
    def branchConfig = jobConfig?.branches?."$env.BRANCH_NAME" ?: [:]
    if ( branchConfig.nodeLabel ) {
        echo "Using branch specific node label ${branchConfig.nodeLabel}"
        return branchConfig.nodeLabel
    } else {
        def label = globalConfig.availableOperatingSystems[operatingSystem]
        if ( !label )
            error("Unknown operating system ${operatingSystem}. Available operating systems: ${globalConfig.availableOperatingSystems}")
        echo "Using operating-system ${operatingSystem} specific node label ${label}"
        return label
    }
}

def additionalMavenParams(def jobConfig) {
    def branchConfig = jobConfig?.branches?."$env.BRANCH_NAME" ?: [:]
    return branchConfig.additionalMavenParams ?
        branchConfig.additionalMavenParams : jobConfig.additionalMavenParams
}

def defineStage(def globalConfig, def jobConfig, def jdkVersion, def operatingSystem, boolean isReferenceStage, boolean shouldDeploy) {

    def goal = jobConfig.mavenGoal ? jobConfig.mavenGoal : ( isReferenceStage ? "deploy" : "verify" )
    def additionalMavenParams = additionalMavenParams(jobConfig)
    def jenkinsJdkLabel = jenkinsJdkLabel(jdkVersion, globalConfig)

    // do not deploy artifacts built from PRs or feature branches
    // also do not deploy non-SNAPSHOT versions
    if ( goal == "deploy" && !shouldDeploy ) {
        goal = "verify"
        echo "Maven goal set to ${goal} since branch is not master ( ${env.BRANCH_NAME} ) or version is not snapshot"
    }

    def invocation = {
        if ( isReferenceStage ) {
            if ( goal == "deploy" && shouldDeploy ) {
                // this must be an absolute path to always refer to the same directory (for each Maven module in a reactor)
                String localRepoPath = "${pwd()}/.local-snapshots-dir" // must also be outside target, as target is cleaned too late
                // Make sure the directory is wiped.
                dir(localRepoPath) {
                    deleteDir()
                }
                // deploy to local directory (all artifacts from a reactor) 
                additionalMavenParams = "${additionalMavenParams} -DaltDeploymentRepository=snapshot-repo::default::file:${localRepoPath}"
            }
            // calculate coverage with jacoco (for subsequent evaluation by SonarQube)
            additionalMavenParams = "${additionalMavenParams} -Pjacoco-report"
        }
        checkout scm
        withMaven(maven: globalConfig.mvnVersion, jdk: jenkinsJdkLabel,
            mavenLocalRepo: '.repository', // use dedicated Maven repository as long as proper locking is not supported, https://lists.apache.org/thread/yovswz70v3f4d2b5ofyoqymvg9lbmzrg
            options: [
                artifactsPublisher(disabled: !isReferenceStage),
                junitPublisher(disabled: !isReferenceStage),
                openTasksPublisher(disabled: !isReferenceStage),
                dependenciesFingerprintPublisher(disabled: !isReferenceStage)
            ] ) {
            String mvnCommand = "mvn -U -B -e clean ${goal} ${additionalMavenParams} -Dci"
            if (isUnix()) {
                sh mvnCommand
            } else {
                bat mvnCommand
            }
        }
        if ( isReferenceStage && jobConfig.archivePatterns ) {
            archiveArtifacts(artifacts: SlingJenkinsHelper.jsonArrayToCsv(jobConfig.archivePatterns), allowEmptyArchive: true)
        }
        if ( isReferenceStage && goal == 'deploy' && shouldDeploy ) {
            // Stash the build results from the local deployment directory so we can deploy them on another node
            stash name: 'local-snapshots-dir', includes: '.local-snapshots-dir/**'
        }
    }
    
    def jenkinsNodeLabel = jenkinsNodeLabel(operatingSystem, jobConfig, globalConfig)
    return {
        node(jenkinsNodeLabel) {
            dir(jenkinsJdkLabel) { // isolate parallel builds on same node
                timeout(time: 30, unit: 'MINUTES') {
                    checkout scm
                    stage("Maven Build (Java ${jdkVersion}, ${goal})") {
                        echo "Running on node ${env.NODE_NAME}"
                        invocation.call()
                    }
                }
                if ( isReferenceStage ) {
                    // SonarQube must be executed on the same node in order to reuse artifact from the Maven build
                    if ( jobConfig.sonarQubeEnabled ) {
                        stage('Analyse with SonarCloud') {
                            timeout(time: 30, unit: 'MINUTES') {
                                analyseWithSonarCloud(globalConfig, jobConfig)
                            }
                        }
                    }
                }
            }
        }
    }
}

def analyseWithSonarCloud(def globalConfig, def jobConfig) {
    // this might fail if there are no jdks defined, but that's always an error
    // also, we don't activate any Maven publisher since we don't want this part of the
    // build tracked, but using withMaven(...) allows us to easily reuse the same
    // Maven and JDK versions
    def additionalMavenParams = additionalMavenParams(jobConfig)
    def isPrBuild = env.BRANCH_NAME.startsWith("PR-")

    // As we don't have the global SonarCloud conf for now, we can't use #withSonarQubeEnv so we need to set the following props manually
    def sonarcloudParams="-Dsonar.host.url=https://sonarcloud.io -Dsonar.organization=apache -Dsonar.projectKey=apache_${jobConfig.repoName} -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco-merged/jacoco.xml ${jobConfig.sonarQubeAdditionalParams}"
    if ( jobConfig.sonarQubeUseAdditionalMavenParams ) {
        sonarcloudParams="${sonarcloudParams} ${additionalMavenParams}"
    }
    // Params are different if it's a PR or if it's not
    // Note: soon we won't have to handle that manually, see https://jira.sonarsource.com/browse/SONAR-11853
    if ( isPrBuild ) {
        sonarcloudParams="${sonarcloudParams} -Dsonar.pullrequest.branch=${CHANGE_BRANCH} -Dsonar.pullrequest.base=${CHANGE_TARGET} -Dsonar.pullrequest.key=${CHANGE_ID}"
    } else if ( isOnMainBranch() ) {
        sonarcloudParams="${sonarcloudParams} -Dsonar.branch.name=${BRANCH_NAME}"
    }
    static final String SONAR_PLUGIN_GAV = 'org.sonarsource.scanner.maven:sonar-maven-plugin:3.9.1.2184'
    // Alls params are set, let's execute using #withCrendentials to hide and mask Robert's token
    withCredentials([string(credentialsId: 'sonarcloud-token-rombert', variable: 'SONAR_TOKEN')]) {
        // always build with Java 17 (that is the minimum version supported: https://docs.sonarcloud.io/appendices/scanner-environment/)
        withMaven(maven: globalConfig.mvnVersion,
            jdk: jenkinsJdkLabel(17, globalConfig),
            publisherStrategy: 'EXPLICIT') {
                try {
                    String mvnCommand = "mvn -B -e ${SONAR_PLUGIN_GAV}:sonar ${sonarcloudParams}"
                    if (isUnix()) {
                        sh mvnCommand
                    } else {
                        bat mvnCommand
                    }
                } catch ( Exception e ) {
                    // TODO - we should check the actual failure cause here, but see
                    // https://stackoverflow.com/questions/55742773/get-the-cause-of-a-maven-build-failure-inside-a-jenkins-pipeline/55744122
                    echo "Marking build unstable due to mvn sonar:sonar failing. See https://cwiki.apache.org/confluence/display/SLING/SonarCloud+analysis for more info."
                    currentBuild.result = 'UNSTABLE'
                }
        }
    }
}

def deployToNexus(def globalConfig) {
    node('nexus-deploy') {
        timeout(60) {
            echo "Running on node ${env.NODE_NAME}"
            // first clear workspace
            deleteDir()
            // Nexus deployment needs pom.xml
            checkout scm
            // Unstash the previously stashed build results.
            unstash name: 'local-snapshots-dir'
            // https://www.mojohaus.org/wagon-maven-plugin/merge-maven-repos-mojo.html
            static final String WAGON_PLUGIN_GAV = "org.codehaus.mojo:wagon-maven-plugin:2.0.2"
            String mavenArguments = "${WAGON_PLUGIN_GAV}:merge-maven-repos -Dwagon.target=https://repository.apache.org/content/repositories/snapshots -Dwagon.targetId=apache.snapshots.https -Dwagon.source=file:${pwd()}/.local-snapshots-dir"
            withMaven(maven: globalConfig.mvnVersion,
                     jdk: jenkinsJdkLabel(11, globalConfig),
                     publisherStrategy: 'EXPLICIT') {
                         String mvnCommand = "mvn ${mavenArguments}"
                         if (isUnix()) {
                             sh mvnCommand
                         } else {
                             bat mvnCommand
                         }
            }
        }
    }
}

boolean getShouldDeploy() {
    // check branch name
    if ( !isOnMainBranch() ) {
        return false
    }
    // check version
    def mavenPom = readMavenPom()
    def mavenVersion = mavenPom.version ?: mavenPom.parent.version
    def isSnapshot = mavenVersion.endsWith('-SNAPSHOT')
    if ( !isSnapshot ) {
        return false
    }
    return true
}

boolean isOnMainBranch() {
    return env.BRANCH_NAME == 'master'
}
