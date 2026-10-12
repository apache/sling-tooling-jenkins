import org.apache.sling.jenkins.SlingJenkinsHelper;

def call(Map params = [:]) {

    def globalConfig = [
        // https://cwiki.apache.org/confluence/x/kRLiAw (most are Eclipse Temurin distributions of Open JDK from Adoptium)
        availableJDKs : [ 8: 'jdk_1.8_latest', 9: 'jdk_1.9_latest', 10: 'jdk_10_latest', 11: 'jdk_11_latest',
                          12: 'jdk_12_latest', 13: 'jdk_13_latest', 14: 'jdk_14_latest', 15: 'jdk_15_latest',
                          16: 'jdk_16_latest', 17: 'jdk_17_latest', 18: 'jdk_18_latest', 19: 'jdk_19_latest', 
                          20: 'jdk_20_latest', 21: 'jdk_21_latest', 22: 'jdk_22_latest', 23: 'jdk_23_latest',
                          24: 'jdk_24_latest', 25: 'jdk_25_latest', 26: 'jdk_26_latest'],
        // https://cwiki.apache.org/confluence/x/cRTiAw
        mvnVersion : 'maven_3_latest',
        // maps values to node labels (available ones in https://cwiki.apache.org/confluence/x/ViZ4CQ)
        availableOperatingSystems : ['windows' : 'Windows', 'linux': 'ubuntu', 'linux-arm': 'arm', 'ubuntu': 'ubuntu'],
        mainNodeLabel : 'ubuntu'
    ]

    // A dedicated job can use the same library without running the Maven build.
    if ( params.llmaoOnly == true ) {
        runLlmaoOnlyBuild(globalConfig, params)
        return
    }

    def jobConfig = [
        jdks: [17,21],
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
        sonarQubeAdditionalParams: '',
        buildTimeout: 30 // default timeout in minutes
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
                                String mvnCommand = "mvn --show-version -U -B -e clean compile ${additionalMavenParams(jobConfig)}"
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

def runLlmaoOnlyBuild(def globalConfig, Map params) {
    def llmaoConfig = [
        llmaoRebuildFrequency: '@weekly',
        llmaoModel: 'gemma4-26b',
        llmaoMaxTokens: 4096,
        llmaoInput: '',
        llmaoInputFile: ''
    ]

    node(globalConfig.mainNodeLabel) {
        timeout(time: 30, unit: 'MINUTES') {
            stage('Init') {
                checkout scm
                if ( fileExists('.sling-module.json') ) {
                    def overrides = readJSON(file: '.sling-module.json', returnPojo: true).jenkins ?: [:]
                    llmaoConfig.keySet().each { key ->
                        if ( overrides.containsKey(key) )
                            llmaoConfig[key] = overrides[key]
                    }
                }
                // Explicit library arguments take precedence over module settings.
                llmaoConfig.keySet().each { key ->
                    if ( params.containsKey(key) )
                        llmaoConfig[key] = params[key]
                }
                if ( !(llmaoConfig.llmaoMaxTokens instanceof Integer) || llmaoConfig.llmaoMaxTokens <= 0 )
                    error('llmaoMaxTokens must be a positive integer.')
            }

            stage('Configure Job') {
                def jobTriggers = []
                // Standalone Pipeline jobs have no BRANCH_NAME. Feature branches
                // in a multibranch job remain available for manual testing only.
                if ( llmaoConfig.llmaoRebuildFrequency && (!env.BRANCH_NAME || isOnMainBranch()) )
                    jobTriggers.add(cron(llmaoConfig.llmaoRebuildFrequency))
                properties([
                    pipelineTriggers(jobTriggers),
                    buildDiscarder(logRotator(numToKeepStr: '10')),
                    disableConcurrentBuilds()
                ])
            }

            def contextData
            stage('Prepare LLMAO Input') {
                contextData = llmaoConfig.llmaoInputFile ?
                    readFile(file: llmaoConfig.llmaoInputFile, encoding: 'UTF-8').trim() :
                    llmaoConfig.llmaoInput.trim()
                if ( !contextData )
                    error('Provide llmaoInput or llmaoInputFile for the LLMAO-only job.')
            }

            def updateStatus
            def projectWorkspace = pwd()
            // Keep generated files outside the source checkout; leave the input file untouched.
            dir("${pwd(tmp: true)}/llmao") {
                def containerName = sh(
                    script: 'printf "sling-llmao-%s" "$(cat /proc/sys/kernel/random/uuid)"',
                    returnStdout: true
                ).trim()
                // A unique directory prevents old usage from leaking into a new build.
                def reportDirectory = "outputs/${containerName}"
                withEnv([
                    "LLMAO_CONTAINER=${containerName}",
                    'LLMAO_IMAGE=sling-llmao-pi:1.1.0-jdks8-21-v2',
                    "LLMAO_MODEL=${llmaoConfig.llmaoModel}",
                    "LLMAO_WORKSPACE=${projectWorkspace}",
                    "LLMAO_REPORT_DIR=${pwd()}/${reportDirectory}"
                ]) {
                    try {
                        stage('Submit to LLMAO') {
                            // Build before binding credentials or capturing stdout. Docker
                            // reuses the cached layers on subsequent runs on this executor.
                            dir('image') {
                                writeFile file: 'Dockerfile', text: libraryResource('org/apache/sling/jenkins/llmao/Dockerfile')
                                sh 'docker build --tag "$LLMAO_IMAGE" .'
                            }
                            dir('inputs') {
                                writeFile file: 'run-pi.sh', text: libraryResource('org/apache/sling/jenkins/llmao/run-pi.sh')
                                writeFile file: 'tool-output-logger.mjs', text: libraryResource('org/apache/sling/jenkins/llmao/tool-output-logger.mjs')
                                writeFile file: 'usage-reporter.mjs', text: libraryResource('org/apache/sling/jenkins/llmao/usage-reporter.mjs')
                                writeFile file: 'system-prompt.txt', text: 'You are a coding assistant working on the checked-out project. Follow the relevant supplied Apache Sling skills; use update-sling-parent-pom for parent POM upgrades. Complete the requested update and its required verification. Java 21 is the default; mvn8, mvn11, mvn17, and mvn21 run Maven with the corresponding JDK. Do not stage, unstage, commit, push, switch branches, or change Git metadata. Respond with exactly SUCCESS if the update and verification succeeded, or FAILURE otherwise. Do not include punctuation or explanation.', encoding: 'UTF-8'
                                writeFile file: 'prompt_input.txt', text: contextData, encoding: 'UTF-8'
                                writeJSON file: 'models.json', json: [
                                    providers: [llmao: [
                                        baseUrl: 'https://llm.apache.org/v1',
                                        api: 'openai-completions',
                                        // Literal environment reference, never the secret itself.
                                        apiKey: '${LLMAO_PAT}',
                                        models: [[
                                            id: llmaoConfig.llmaoModel,
                                            input: ['text'],
                                            maxTokens: llmaoConfig.llmaoMaxTokens,
                                            samplingParams: [temperature: 0.0]
                                        ]]
                                    ]]
                                ]
                                writeJSON file: 'settings.json', json: [
                                    retry: [
                                        enabled: true,
                                        maxRetries: 10,
                                        baseDelayMs: 60000,
                                        maxAgentDelayMs: 60000,
                                        provider: [maxRetries: 0]
                                    ],
                                    compaction: [enabled: false],
                                    cacheWarming: 'off'
                                ]
                            }
                            withCredentials([string(credentialsId: 'llmao-pat', variable: 'LLMAO_PAT')]) {
                                updateStatus = sh(
                                    script: libraryResource('org/apache/sling/jenkins/llmao/run-docker.sh'),
                                    returnStatus: true
                                )
                            }
                        }
                    } finally {
                        try {
                            // Stop writes before archiving, including on Jenkins cancellation.
                            sh '''
                                set +x
                                docker rm --force "$LLMAO_CONTAINER" >/dev/null 2>&1 || true
                            '''
                            dir(reportDirectory) {
                                publishLlmaoUsage(llmaoConfig.llmaoModel, updateStatus)
                            }
                        } finally {
                            sh '''
                                set +x
                                rm -f image/Dockerfile inputs/models.json inputs/settings.json \\
                                  inputs/system-prompt.txt inputs/prompt_input.txt inputs/run-pi.sh \\
                                  inputs/tool-output-logger.mjs inputs/usage-reporter.mjs
                                rm -f "$LLMAO_REPORT_DIR/llmao-usage.json" "$LLMAO_REPORT_DIR/llmao-usage.json.tmp"
                                rmdir "$LLMAO_REPORT_DIR" >/dev/null 2>&1 || true
                            '''
                        }
                    }
                }
            }

            stage('Process LLMAO Response') {
                if ( updateStatus == 0 ) {
                    echo 'Pi completed the project update successfully.'
                } else {
                    error("Pi could not complete the project update (exit code ${updateStatus}).")
                }
            }
        }
    }
}

def publishLlmaoUsage(def model, def updateStatus) {
    def report = fileExists('llmao-usage.json') ? readJSON(file: 'llmao-usage.json', returnPojo: true) : [
        schemaVersion: 1, provider: 'llmao', model: model,
        state: 'unknown', aborted: null, usageStatus: 'unavailable',
        requests: null, responsesWithUsage: null, responsesWithoutUsage: null, tokens: null
    ]
    report.build = [job: env.JOB_NAME, number: env.BUILD_NUMBER, url: env.BUILD_URL]
    report.result = updateStatus == null ? 'INCOMPLETE' : updateStatus == 0 ? 'SUCCESS' : 'FAILURE'
    report.exitCode = updateStatus
    def description = "LLMAO: ${report.result}"
    if ( report.tokens != null ) {
        def tokens = report.tokens
        echo "LLMAO observed tokens: input=${tokens.input}, output=${tokens.output}, cacheRead=${tokens.cacheRead}, cacheWrite=${tokens.cacheWrite}, total=${tokens.total} (${report.usageStatus})."
        description += " | ${tokens.total} tokens${report.usageStatus == 'partial' ? ' (partial)' : ''}"
    } else {
        echo 'LLMAO token usage unavailable; no gateway-reported usage was collected.'
        description += ' | tokens unavailable'
    }
    currentBuild.description = description
    writeJSON file: 'llmao-usage.json', json: report
    archiveArtifacts artifacts: 'llmao-usage.json', allowEmptyArchive: false
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
            // generate javadocs to detect illegal javadoc markup in sources
            additionalMavenParams = "javadoc:javadoc ${additionalMavenParams}"
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
            String mvnCommand = "mvn --show-version -U -B -e clean ${goal} ${additionalMavenParams} -Dci"
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
                timeout(time: jobConfig.buildTimeout, unit: 'MINUTES') {
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
