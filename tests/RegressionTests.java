import java.util.*;

public final class RegressionTests {
    private static int checks;
    private static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    public static void main(String[] args) throws Exception {
        check(Collections.list(RegressionTests.class.getClassLoader().getResources("org/lwjgl/Sys.class")).size()==1,"only one LWJGL runtime on classpath");
        check(Math.abs(ArenaRules.angleDelta((float)Math.toRadians(179),(float)Math.toRadians(-179))-(float)Math.toRadians(2))<.0001,"short angle across wrap");
        check(ArenaRules.segmentDistanceSquared(0,0,-10,0,10,0)==0,"swept projectile hits crossed target");
        check(Math.abs(ArenaRules.segmentDistanceSquared(0,2,-10,0,10,0)-4)<.0001,"projectile miss");
        check(ArenaRules.segmentDistanceSquared(3,4,0,0,0,0)==25,"stationary collision");
        check(ArenaRules.finite(Float.NaN,3)==3 && ArenaRules.finite(Float.POSITIVE_INFINITY,4)==4,"non-finite input");
        float one=ArenaRules.damping(9,1f),split=1-(1-ArenaRules.damping(9,.5f))*(1-ArenaRules.damping(9,.5f));
        check(Math.abs(one-split)<.00001,"frame independent damping");
        check(ArenaRules.bossKind(3)==4 && ArenaRules.bossKind(6)==5 && ArenaRules.bossKind(9)==6 && ArenaRules.bossKind(12)==4,"three recurring bosses");
        check(!ArenaRules.bossWave(0) && ArenaRules.bossWave(3),"boss schedule");
        check(ArenaRules.waveBudget(100,4)<=30,"bounded wave population");
        check(ArenaRules.levelCost(3)>ArenaRules.levelCost(2),"progression cost");
        MatchProtocol sender=new MatchProtocol("match-A"),receiver=new MatchProtocol("match-A");
        List<String> acknowledgements=new ArrayList<>(),retry=new ArrayList<>();
        String wire=sender.send("ACT2|1|2|1",true,0);
        check("ACT2|1|2|1".equals(receiver.receive(wire,acknowledgements::add)),"reliable delivery");
        check(receiver.receive(wire,acknowledgements::add)==null,"duplicate action suppression");
        check(acknowledgements.size()==2,"duplicates are re-acknowledged");
        sender.retry(.3,retry::add);check(retry.size()==1,"lost ack causes retry");
        sender.receive(acknowledgements.get(1),s->{});
        check(sender.pendingCount()==0,"ack removes pending");
        sender.retry(.7,retry::add);check(retry.size()==1,"acked event not resent");
        MatchProtocol nextMatch=new MatchProtocol("match-B");
        check(nextMatch.receive(wire,s->{throw new AssertionError("old match acknowledged");})==null,"old session rejected");
        check(receiver.receive("TS2|match-A|R|oops|foo",s->{})==null,"bad sequence rejected");
        String text="MAP_EVT|карта|змейка";check(text.equals(receiver.receive(sender.send(text,false,1),s->{})),"UTF-8 payload");
        sender.send("never acknowledged",true,1);sender.retry(12,s->{});
        check(sender.pendingCount()==0,"expired retries reclaimed");
        boolean oversize=false;try{sender.send("x".repeat(9000),true,1);}catch(IllegalArgumentException e){oversize=true;}
        check(oversize,"packet bound");
        System.out.println("PASS: "+checks+" regression checks");
    }
}
