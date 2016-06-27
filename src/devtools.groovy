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

boolean isTimeOptionCorrect
String hour
String minutes
String seconds

if (opts.arguments().size() == 2) {
    option = opts.arguments().get(1)

    // Validate time option
    if (command.equals("tomorrow")) {
        String[] time = option.split(":")

        if (time.length == 3) {
            hour = time[0]
            minutes = time[1]
            seconds = time[2]

            if (Integer.parseInt(hour) < 24 && Integer.parseInt(hour) >= 0)
                isTimeOptionCorrect = true

            if (Integer.parseInt(minutes) < 60 && Integer.parseInt(minutes) >= 0)
                isTimeOptionCorrect = true

            if (Integer.parseInt(seconds) < 60 || Integer.parseInt(seconds) >= 0)
                isTimeOptionCorrect = true

            hour = fixFormat(hour)
            minutes = fixFormat(minutes)
            seconds = fixFormat(seconds)

        }

        if (!isTimeOptionCorrect)
            println("Time option is not correct, it will not be taken into consideration.")

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
        adbcmd = buildNowCmd()
        println(adbcmd)
        executeADBCommand(adbcmd)
        break

    case "tomorrow":
        // Retrieve device date
        adbcmd = "shell date"
        adbCmdOutput = executeADBCommand(adbcmd)
        
        // Convert device date string to Date Object
        Date deviceDate = Date.parse("EEE MMM dd HH:mm:ss z yyyy", adbCmdOutput)

        // Tomorrow date based on device date
        Date tomorrow = DateGroovyMethods.next(deviceDate)
        def year = tomorrow.format("yyy")
        def month = tomorrow.format("MM")
        def day = tomorrow.format("dd")

        if (option == null || !isTimeOptionCorrect) {
            // No time option provided: set Time to the device time
            hour = deviceDate.format("HH")
            minutes = deviceDate.format("mm")
            seconds = deviceDate.format("ss")
            
        }
        
        adbcmd = "shell date -s " + year + month + day + "." + hour + minutes + seconds
        
        print("Device Date from adb            : " + adbCmdOutput)
        println("Device Date after parse         : " + deviceDate)
        println("Setting device date to tomorrow : " + tomorrow)
        println("tomorrow command : " + adbcmd)
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

def buildNowCmd() {
        Calendar calendar = Calendar.getInstance()
        println("Setting device date to : " + DateGroovyMethods.format(calendar.getTime(), "dd/MMM/yyyy HH:mm:ss"))

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

        return adbcmd
}

void kickSystemService() {
    def proc
    int SYSPROPS_TRANSACTION = 1599295570 // ('_'<<24)|('S'<<16)|('P'<<8)|'R'

    def pingService = "shell service call activity $SYSPROPS_TRANSACTION"
    executeADBCommand(pingService)
}

def executeADBCommand(String adbcmd) {
    if(deviceIds.size == 0) {
        println("no devices connected")
        System.exit(-1)
    }
    def proc
    deviceIds.each { deviceId ->
        def adbConnect = "$adbExec -s $deviceId $adbcmd"
        if(verbose)
            println("Executing $adbConnect")
        proc = adbConnect.execute()
        proc.waitFor()
    }
    return proc.text
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
