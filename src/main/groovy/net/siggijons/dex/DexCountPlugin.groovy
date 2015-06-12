package net.siggijons.dex

import aQute.service.reporter.Report
import com.android.build.gradle.AppExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.api.ApplicationVariant
import groovy.json.JsonOutput
import groovy.text.SimpleTemplateEngine
import org.apache.commons.io.FilenameUtils
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.reporting.ReportingExtension
import org.gradle.api.tasks.TaskAction
import org.gradle.util.GFileUtils

/**
 * Created by siggijons on 09/06/15.
 */
class DexCountPlugin implements Plugin<Project> {

    final static String GROUP_NAME = "DexCount"

    @Override
    void apply(Project project) {

        if (project.plugins.hasPlugin(AppPlugin)) {
            AppExtension android = project.android
            android.applicationVariants.all { ApplicationVariant variant ->

                DexCountTask task = project.tasks.create("count${variant.name.capitalize()}DexMethods", DexCountTask)
                task.group = GROUP_NAME
                task.description = "Count '${variant.name}' dex methods"
                task.applicationVariant = variant
                task.variantName = variant.name
                task.outputs.upToDateWhen { false }

                task.dependsOn variant.assemble
            }
        }

        project.task('dexcount') << {
            println("${project.dexcount.message}")
        }
    }
}

class DexCountTask extends DefaultTask {

    ApplicationVariant applicationVariant
    File applicationFile
    String variantName

    DexCountTask() {
        super()
        this.description = "Counts dex methods in an apk file"
    }

    @TaskAction
    def count() throws IOException {

        applicationVariant.outputs.each {
            if (FilenameUtils.isExtension(it.outputFile.getName(), "apk")) {
                applicationFile = it.outputFile
                return true
            }
        }

        if (!applicationFile?.exists())
        {
            throw new IllegalArgumentException("No application file found")
        }

        def report = new DexCount().countMethods(applicationFile.absolutePath)
        println "Counted ${report.totalMethods} methods in ${report.dexFileMethods.size()} dex files ${report.dexFileMethods}"
//        printNode(report.root, 0)

        def reportsDir = new File(
                project.buildDir,
                ReportingExtension.DEFAULT_REPORTS_DIR_NAME + "/dex-count" + "/" + variantName
        )
        reportsDir.mkdirs()

        def dataFile = new File(reportsDir, "tree-map-data.js")

        dataFile.write """\
            var totalMethods = $report.totalMethods;
            var gaugeData = ${JsonOutput.toJson(report.generateGaugesData())};
            var treeMapData = ${JsonOutput.toJson(report.generateTreeMapReportData())};
        """

        def reportFile = new File(reportsDir, "report.html")

        this.getClass().getResource( '/html/charts.html' ).withInputStream { ris ->
            reportFile.withOutputStream { fos ->
                fos << ris
            }
        }
    }

    def printNode(DexCount.Node node, int indent)
    {
        println(" ".multiply(indent) + node.name + ": " + node.count)
        node.children.each { k,v ->
            printNode(v, indent+1)
        }
    }

}
