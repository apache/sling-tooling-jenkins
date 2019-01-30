package sling;

class SlingModuleParser {

    def fileName;
    
    // defaults for the build
    def buildDesc = [
        jdks: [8],
        downstreamProjects: [],
        archivePatterns: [],
        mavenGoal: 'install',
        additionalMavenParams: '',
        rebuildFrequency: '@weekly',
        enableXvfb: false,
        enabled: true
    ]

    SlingModuleParser(fileName) {
        this.fileName = fileName;        
    }

    def parse() {
        def slingMod = new XmlSlurper().parseText(overrides)
        if ( slingMod?.jenkins?.jdks ) {
            def jdks = []
            slingMod.jenkins.jdks.jdk.each { jdks.add it.text() }
            buildDesc.jdks = jdks
        }
    }
}