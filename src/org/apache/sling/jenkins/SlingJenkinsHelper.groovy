/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.jenkins;

/*
 * Helper which provides reusable building blocks for Sling Pipeline jobs
 *
 * <p>This class is implicity ( i.e. no class declaration ) in order to inherit all the available 
 * bindings of the Jenkins scripted pipeline.</p>
 * 
 * <p>The <tt>runWithErrorHandling</tt> method accepts a closure which should be a scripted pipeline
 * execution block, generating the <em>Init</em> and <em>Configure Job</em> steps automatically. The
 * closure will receieve a single argument of type <tt>Map</tt>, which holds the job configuration.
 * The job configuration is build from the Sling module descriptor, if present.</p>
 * 
 * @see <a href="https://jenkins.io/doc/book/pipeline/shared-libraries/">Pipeline shared libraries</a>
 * @see <a href="https://cwiki.apache.org/confluence/display/SLING/Sling+module+descriptor">Sling module descriptor</a>
 */

// workaround for "Scripts not permitted to use method net.sf.json.JSONArray join java.lang.String"
def static jsonArrayToCsv(net.sf.json.JSONArray items) {
    def result = []
    items.each { item ->
        result.add(item)
    }
    return result.join(',')
}


def runWithErrorHandling(Closure build) {

    def jobConfig = [
        jdks: [8],
        upstreamProjects: [],
        archivePatterns: [],
        mavenGoal: '',
        additionalMavenParams: '',
        rebuildFrequency: '@weekly',
        enabled: true,
        emailRecipients: []
    ]

    try {
        timeout(time:15, unit: 'MINUTES', activity: true) {

            stage('Init') {
                checkout scm
                def url = sh(returnStdout: true, script: 'git config remote.origin.url').trim()
                jobConfig.repoName = url.substring(url.lastIndexOf('/') + 1).replace('.git', '');
                if ( fileExists('.sling-module.json') ) {
                    overrides = readJSON file: '.sling-module.json'
                    echo "Jenkins overrides: ${overrides.jenkins}"
                    overrides.jenkins.each { key,value ->
                        jobConfig[key] = value;
                    }
                }
                echo "Final job config: ${jobConfig}"
            }
                
            stage('Configure Job') {
                def upstreamProjectsCsv = jobConfig.upstreamProjects ? 
                    jsonArrayToCsv(jobConfig.upstreamProjects) : ''
                def jobTriggers = []
                if ( env.BRANCH_NAME == 'master' )
                    jobTriggers.add(cron(jobConfig.rebuildFrequency))
                if ( upstreamProjectsCsv )
                    jobTriggers.add(upstream(upstreamProjects: upstreamProjectsCsv, threshold: hudson.model.Result.SUCCESS))

                properties([
                    pipelineTriggers(jobTriggers)
                ])
            }

            build.call(jobConfig)
        }
    // exception handling copied from https://github.com/apache/maven-jenkins-lib/blob/d6c76aaea9df19ad88439eba4f9d1ad6c9e272bd/vars/asfMavenTlpPlgnBuild.groovy
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
        // this ambiguous condition means a user probably aborted
        if (e.causes.size() == 0) {
            currentBuild.result = "ABORTED"
        } else {
            currentBuild.result = "FAILURE"
        }
        throw e
    } catch (hudson.AbortException e) {
        // this ambiguous condition means during a shell step, user probably aborted
        if (e.getMessage().contains('script returned exit code 143')) {
            currentBuild.result = "ABORTED"
        } else {
            currentBuild.result = "FAILURE"
        }
        throw e
    } catch (InterruptedException e) {
        currentBuild.result = "ABORTED"
        throw e
    } catch (Throwable e) {
        currentBuild.result = "FAILURE"
        throw e
    } finally {
        stage("Notifications") {
            sendNotifications(jobConfig)
        }
    }
}

def sendNotifications(def jobConfig) {

    if ( env.BRANCH_NAME != 'master' ) {
        echo "Not sending notifications on branch name ${env.BRANCH_NAME} != 'master'"
        return
    }

    def recipients = jobConfig['emailRecipients']

    if ( !recipients ) {
        echo "No recipients defined, not sending notifications."
        return
    }

    // values described at https://javadoc.jenkins-ci.org/hudson/model/Result.html
    // Note that we don't handle consecutive failures to prevent mail spamming

    String current = currentBuild.result
    String previous = currentBuild.getPreviousBuild()?.result
    def change = null;
    def recipientProviders = []

    // 1. changes from success or unknown to non-success
    if ( (previous == null || previous == "SUCCESS") && current != "SUCCESS" ) {
        change = "BROKEN"
        recipientProviders = [[$class: 'CulpritsRecipientProvider']]
    }

    // 2. changes from non-success to success
    if ( (previous != null && previous != "SUCCESS") && current == "SUCCESS" )
        change = "FIXED"

    if ( change == null ) {
        echo "No change in status, not sending notifications."
        return
    }
    
    echo "Status change is ${change}, notifications will be sent."

    def subject = "[Jenkins] ${currentBuild.fullDisplayName} is ${change}"
    def body = """Please see ${currentBuild.absoluteUrl} for details.

No further emails will be sent until the status of the build is changed.
"""

    if ( change == "BROKEN") {
        body += "Build log follows below:\n\n"
        body += '${BUILD_LOG}'
    }

    emailext subject: subject, body: body, replyTo: 'dev@sling.apache.org', recipientProviders: recipientProviders, to: jsonArrayToCsv(recipients)
}