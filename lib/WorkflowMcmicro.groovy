//
// This file holds several functions specific to the workflow/mcmicro.nf in the nf-core/mcmicro pipeline
//

import groovy.text.SimpleTemplateEngine
import org.yaml.snakeyaml.Yaml

class WorkflowMcmicro {

    /** 
    * Validates workflow parameters against a schema
    *
    * @param wfp workflow parameters
    * @param fns filename of the schema
    */
    static def validateWFParams(wfp, fns) {
        // Parse the schema
        Map schema = new Yaml().load(new File(fns))

        // Validate workflow parameters against the schema
        wfp.each{ key, val ->
            // Check for deprecated parameters
            if(schema.deprecated.containsKey(key)) {
                String msg = "Parameter " + key + " is deprecated; " +
                    "please use " + schema.deprecated[key]
                throw new Exception(msg)
            }

            // Check for unrecognized parameters
            if(!schema.workflow.contains(key)) {
                throw new Exception("Unrecognized parameter " + key)
            }
        }

        // Additional custom validation(s)
        if(wfp['segmentation-recyze'] && 
        !wfp.containsKey('segmentation-channel')) {
            String msg = "Segmentation-recyze requested but no " +
                "segmentation-channel provided"
            throw new Exception(msg)
        }
    }

    static def camel2snake(s) {
        s.replaceAll( /([A-Z])/, /-$1/ ).toLowerCase()
    }

    static def collectNames(m) {
        // Recurse on Maps and lists
        if(m instanceof Map) {
            if(m.containsKey('name')) return m.name
            return m.collect{key, val -> collectNames(val)}.flatten()
        }
        else if(m instanceof List)
            return m.collect(it -> collectNames(it)).flatten()
        
        else return null
    }

    static def cleanParams(pars, mspecs) {
        Map workflow = [:]
        Map options = [:]

        // Identify all module names
        def names = collectNames(mspecs)

        // Clean up the parameter list
        // Separate workflow parameters from module options
        pars.findAll{ key, val ->
            camel2snake(key) == key &&
            !['in', 'cont-pfx', 'roadie', 'modules', 'params'].contains(key)
        }.each{ key, val ->
            String keyc = key.replaceAll( /-opts$/, '' )
            if(names.contains(keyc))
                options[keyc] = val
            else
                workflow[key] = val
        }

        ['workflow':workflow, 'options':options]
    }

    static def updateMap(orig, repl) {

        // Recurse on Maps
        if((repl instanceof Map) && (orig instanceof Map)) {
            repl.each{ key, val ->
                if( orig.containsKey(key) && 
                ((orig[key] instanceof Map) && (val instanceof Map)) ||
                ((orig[key] instanceof List) && (val instanceof List)) ) {
                    orig[key] = updateMap(orig[key], val)
                }
                else orig[key] = val
            }
        }

        // Match List items by the name field
        else if((repl instanceof List) && (orig instanceof List)) {
            repl.each{ repli ->
                def i = orig.findIndexOf{it.name == repli.name}
                if(i > -1) orig[i] = updateMap(orig[i], repli)
                else orig << repli
            }
        }

        else throw new Exception("New parameter format doesn't match the original")

        orig
    }

    /**
    * Parses MCMICRO parameters
    *
    * @param gp global parameters (usually params in NF space)
    * @param fns filename of the schema
    * @param fnw filename of the default workflow parameters
    */
    
    static def parseParams(gp, fns, fnw) {

        System.out.println "*** Starting parseParams()  ***"

        // Load default MCMICRO parameters (mcp)
        Map mcp = new Yaml().load(new File(fnw))

        // Check for the presence of a parameter file in the project directory
        File pproj = new File("${gp.in}/params.yml")
        if(pproj.exists()) {
            Map mproj = new Yaml().load(pproj)
            updateMap(mcp, mproj)
        }

        // Overwrite the parameters from a user-provided file
        if(gp.containsKey('params')) {
            Map mp = new Yaml().load(new File(gp.params))S
            updateMap(mcp, mp)
        }

        // Override the module specs, if specified
        if(gp.containsKey('modules')) {
            Map mm = new Yaml().load(new File(gp.modules))
            updateMap(mcp.modules, mm)
        }

        // Override workflow parameters and module options with
        //   command-line arguments (cla), as appropriate
        Map cla = cleanParams(gp, mcp.modules)
        updateMap(mcp, cla)
        validateWFParams(mcp.workflow, fns)

        // Filter segmentation modules based on --segmentation
        mcp.modules['segmentation'] = mcp.modules['segmentation'].findAll{
            mcp.workflow.segmentation.contains(it.name)
        }

        // Filter downstream modules based on --downstream
        mcp.modules['downstream'] = mcp.modules['downstream'].findAll{
            mcp.workflow.downstream.contains(it.name)
        }

        mcp
    }
    
    //
    // Check and validate parameters
    //
    public static void initialise(params, log) {
        System.out.println "*** Starting initialise()  ***"
        parseParams(params, "config/schema.yml", "config/defaults.yml")
    }

    //
    // Get workflow summary for MultiQC
    //
    public static String paramsSummaryMultiqc(workflow, summary) {
        String summary_section = ''
        for (group in summary.keySet()) {
            def group_params = summary.get(group)  // This gets the parameters of that particular group
            if (group_params) {
                summary_section += "    <p style=\"font-size:110%\"><b>$group</b></p>\n"
                summary_section += "    <dl class=\"dl-horizontal\">\n"
                for (param in group_params.keySet()) {
                    summary_section += "        <dt>$param</dt><dd><samp>${group_params.get(param) ?: '<span style=\"color:#999999;\">N/A</a>'}</samp></dd>\n"
                }
                summary_section += "    </dl>\n"
            }
        }

        String yaml_file_text  = "id: '${workflow.manifest.name.replace('/','-')}-summary'\n"
        yaml_file_text        += "description: ' - this information is collected when the pipeline is started.'\n"
        yaml_file_text        += "section_name: '${workflow.manifest.name} Workflow Summary'\n"
        yaml_file_text        += "section_href: 'https://github.com/${workflow.manifest.name}'\n"
        yaml_file_text        += "plot_type: 'html'\n"
        yaml_file_text        += "data: |\n"
        yaml_file_text        += "${summary_section}"
        return yaml_file_text
    }

    public static String methodsDescriptionText(run_workflow, mqc_methods_yaml) {
        // Convert  to a named map so can be used as with familar NXF ${workflow} variable syntax in the MultiQC YML file
        def meta = [:]
        meta.workflow = run_workflow.toMap()
        meta["manifest_map"] = run_workflow.manifest.toMap()

        meta["doi_text"] = meta.manifest_map.doi ? "(doi: <a href=\'https://doi.org/${meta.manifest_map.doi}\'>${meta.manifest_map.doi}</a>)" : ""
        meta["nodoi_text"] = meta.manifest_map.doi ? "": "<li>If available, make sure to update the text to include the Zenodo DOI of version of the pipeline used. </li>"

        def methods_text = mqc_methods_yaml.text

        def engine =  new SimpleTemplateEngine()
        def description_html = engine.createTemplate(methods_text).make(meta)

        return description_html
    }
}
