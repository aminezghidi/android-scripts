#!/usr/bin/env groovy
/**
 * Created by dhelleberg on 24/09/14.
 * Improve command line parsing
 */

import org.codehaus.groovy.runtime.DateGroovyMethods

gfx_command_map = ['on' : 'visual_bars', 'off' : 'false', 'lines' : 'visual_lines']
layout_command_map = ['on' : 'true', 'off' : 'false']
overdraw_command_map = ['on' : 'show',  'off' : 'false', 'deut' : 'show_deuteranomaly']
overdraw_command_map_preKitKat = ['on' : 'true',  'off' : 'false']
show_updates_map = ['on' : '0',  'off' : '1']


command_map = ['gfx' : gfx_command_map,
               'layout' : layout_command_map,
               'overdraw' : overdraw_command_map,
               'updates' : show_updates_map]

verbose = false

def cli = new CliBuilder(usage:'devtools.groovy command option')
cli.with {
    v longOpt: 'verbose', 'prints additional output'
}
def opts = cli.parse(args)
if(!opts)
    printHelp("not provided correct option")
if(opts.arguments().size() < 1 || opts.arguments().size() > 2)
    printHelp("you need to provide one or two arguments: command or command and option")
if(opts.v)
    verbose = true

//get args
String command = opts.arguments().get(0)
String option

boolean isTimeOptionCorrect = true
String hour
String minutes
String seconds

if (opts.arguments().size() == 2) {
    option = opts.arguments().get(1)

    if (command.equals("tomorrow")) {
        String[] time = option.split(":")
        hour = time[0]
        minutes = time[1]
        seconds = time[2]

        if (Integer.parseInt(hour) > 23 || Integer.parseInt(hour) < 0)
            isTimeOptionCorrect = false

        if (Integer.parseInt(minutes) > 59 || Integer.parseInt(minutes) < 0)
            isTimeOptionCorrect = false

        if (Integer.parseInt(seconds) > 59 || Integer.parseInt(seconds) < 0)
            isTimeOptionCorrect = false

        // Time format correction
        if (isTimeOptionCorrect) {
            if(hour.length() == 1)
                hour = "0" + hour
            if(minutes.length() == 1)
                minutes = "0" + minutes
            if(seconds.length() == 1)
                seconds = "0" + seconds
        } else {
            println("Time option is not correct, it will not be taken into consideration.")
        }

    }
}

//get adb exec
adbExec = getAdbPath();

//check connected devices
def adbDevicesCmd = "$adbExec devices"
def proc = adbDevicesCmd.execute()
proc.waitFor()

def foundDevice = false
deviceIds = []

proc.in.text.eachLine { //start at line 1 and check for a connected device
        line, number ->
            if(number > 0 && line.contains("device")) {
                foundDevice = true
                //grep out device ids
                def values = line.split('\\t')
                if(verbose)
                    println("found id: "+values[0])
                deviceIds.add(values[0])
            }
}

if(!foundDevice) {
    println("No usb devices")
    System.exit(-1)
}


def adbcmd = ""
switch ( command ) {
    case "gfx" :
        adbcmd = "shell setprop debug.hwui.profile "+gfx_command_map[option]
        executeADBCommand(adbcmd)
        break
    case "layout" :
        adbcmd = "shell setprop debug.layout "+layout_command_map[option]
        executeADBCommand(adbcmd)
        break
    case "overdraw" :
        //tricky, properties have changed over time
        adbcmd = "shell setprop debug.hwui.overdraw "+overdraw_command_map[option]
        executeADBCommand(adbcmd)
        adbcmd = "shell setprop debug.hwui.show_overdraw "+overdraw_command_map_preKitKat[option]
        executeADBCommand(adbcmd)
        break
    case "updates":
        adbcmd = "shell service call SurfaceFlinger 1002 android.ui.ISurfaceComposer"+show_updates_map[option]
        executeADBCommand(adbcmd)
        break
    case "now":
        Calendar calendar = Calendar.getInstance()

        String monthOfYear = fixFormat(String.valueOf((calendar.get(Calendar.MONTH) + 1)))
        String dayOfMonth = fixFormat(String.valueOf(calendar.get(Calendar.DAY_OF_MONTH)))
        String minutesOfHour = fixFormat(String.valueOf(calendar.get(Calendar.MINUTE)))
        String secondsOfMinutes = fixFormat(String.valueOf(calendar.get(Calendar.SECOND)))

        adbcmd = "shell date -s " + 
                    calendar.get(Calendar.YEAR) + 
                    monthOfYear + 
                    dayOfMonth + 
                    "." +
                    calendar.get(Calendar.HOUR_OF_DAY) + 
                    minutesOfHour +
                    secondsOfMinutes

        println(DateGroovyMethods.format(calendar.getTime(), "dd/MMM/yyyy hh:mm:ss"))
        
        println(adbcmd)
        executeADBCommand(adbcmd)

        break
    case "tomorrow":
        Calendar calendar = Calendar.getInstance()
        Date tomorrow = DateGroovyMethods.next(calendar.getTime())
        calendar.setTime(tomorrow)

        String monthOfYear = fixFormat(String.valueOf((calendar.get(Calendar.MONTH) + 1)))

        adbcmd = "shell date -s " + calendar.get(Calendar.YEAR) + monthOfYear + calendar.get(Calendar.DAY_OF_MONTH)

        if (option != null && isTimeOptionCorrect) {
            adbcmd += "." + hour + minutes + seconds
            println(DateGroovyMethods.format(tomorrow, "dd/MMM/yyyy" + " " + hour + ":" + minutes + ":" + seconds))

        } else {
            println(DateGroovyMethods.format(tomorrow, "dd/MMM/yyyy hh:mm:ss"))
        }
        println(adbcmd)
        executeADBCommand(adbcmd)

        break
    default:
        printHelp("could not find the command $command you provided")

}

kickSystemService()

System.exit(0)


String fixFormat(String val) {
    if (val.length() == 1)
        return "0" + val;
    return val;
}



void kickSystemService() {
    def proc
    int SYSPROPS_TRANSACTION = 1599295570 // ('_'<<24)|('S'<<16)|('P'<<8)|'R'

    def pingService = "shell service call activity $SYSPROPS_TRANSACTION"
    executeADBCommand(pingService)
}

void executeADBCommand(String adbcmd) {
    if(deviceIds.size == 0) {
        println("no devices connected")
        System.exit(-1)
    }
    deviceIds.each { deviceId ->
        def proc
        def adbConnect = "$adbExec -s $deviceId $adbcmd"
        if(verbose)
            println("Executing $adbConnect")
        proc = adbConnect.execute()
        proc.waitFor()
    }
}

String getAdbPath() {
    def adbExec = "adb"
    if(isWindows())
        adbExec = adbExec+".exe"
    try {
        def command = "$adbExec"    //try it plain from the path
        command.execute()
        if(verbose)
            println("using adb in "+adbExec)
        return adbExec
    }
    catch (IOException e) {
        //next try with Android Home
        def env = System.getenv("ANDROID_HOME")
        if(verbose)
            println("adb not in path trying Android home")
        if (env != null && env.length() > 0) {
            //try it here
            try {
                adbExec = env + File.separator + "platform-tools" + File.separator + "adb"
                if(isWindows())
                    adbExec = adbExec+".exe"

                def command = "$adbExec"// is actually a string
                command.execute()
                if(verbose)
                    println("using adb in "+adbExec)

                return adbExec
            }
            catch (IOException ex) {
                println("Could not find $adbExec in path and no ANDROID_HOME is set :(")
                System.exit(-1)
            }
        }
        println("Could not find $adbExec in path and no ANDROID_HOME is set :(")
        System.exit(-1)
    }
}

boolean isWindows() {
    return (System.properties['os.name'].toLowerCase().contains('windows'))
}

void printHelp(String additionalmessage) {
    println("usage: devtools.groovy [-v] command option")
    print("command: ")
    command_map.each { command, options ->
        print("\n  $command -> ")
        options.each {
            option, internal_cmd -> print("$option ")
        }
    }
    println()
    println("Error $additionalmessage")
    println()

    System.exit(-1)
}
