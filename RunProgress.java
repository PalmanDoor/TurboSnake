import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Best runs only; no login, streak penalties or purchases. */
public final class RunProgress {
    private static final Properties data=new Properties();
    private static boolean loaded;
    private static File file(){return new File(SnakeApp.getSettingsFile().getParentFile(),"progress.properties");}
    private static void load() {
        if(loaded)return;loaded=true;
        try(InputStream in=new FileInputStream(file())){data.load(in);}catch(IOException ignored){}
    }
    public static synchronized int best(String map,String stat) {
        load();try{return Math.max(0,Integer.parseInt(data.getProperty(map+"."+stat,"0")));}catch(NumberFormatException e){return 0;}
    }
    public static synchronized void record(String map,int wave,int score,int bosses) {
        load();data.setProperty(map+".wave",""+Math.max(wave,best(map,"wave")));
        data.setProperty(map+".score",""+Math.max(score,best(map,"score")));
        data.setProperty(map+".bosses",""+Math.max(bosses,best(map,"bosses")));
        try {
            Path target=file().toPath(),temp=target.resolveSibling("progress.tmp");
            try(OutputStream out=Files.newOutputStream(temp)){data.store(out,"TurboSnake best runs");}
            try {Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException e){SnakeApp.logSafe("Progress",e);}
    }
}
