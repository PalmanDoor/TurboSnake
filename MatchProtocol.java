import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Match-scoped UDP framing. All methods run on the game thread. */
public final class MatchProtocol {
    public static final int VERSION = 2;
    public static final int MAX_PACKET = 8192;
    public static final int MAX_PENDING = 512;
    private final String session;
    private long nextId = 1;
    private final Map<Long, Pending> pending = new LinkedHashMap<>();
    private final Set<Long> received = new LinkedHashSet<>();
    private static final class Pending {
        String wire; double next, expires;
        Pending(String wire, double now) { this.wire=wire; next=now+0.2; expires=now+10; }
    }
    public MatchProtocol(String session) {
        if (session == null || !session.matches("[a-zA-Z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid session");
        this.session=session;
    }
    public String send(String payload, boolean reliable, double now) {
        if (payload == null) throw new IllegalArgumentException("Missing payload");
        long id=nextId++;
        String wire="TS2|"+session+"|"+(reliable?"R":"U")+"|"+id+"|"+payload;
        if (wire.getBytes(StandardCharsets.UTF_8).length > MAX_PACKET) throw new IllegalArgumentException("Datagram too large");
        if (reliable) {
            if (pending.size() >= MAX_PENDING) throw new IllegalStateException("Reliable queue is full");
            pending.put(id,new Pending(wire,now));
        }
        return wire;
    }
    public String receive(String wire, Consumer<String> reply) {
        if (wire==null || wire.length()>MAX_PACKET) return null;
        String[] p=wire.split("\\|",5);
        if (p.length<4 || !"TS2".equals(p[0]) || !session.equals(p[1])) return null;
        long id;
        try { id=Long.parseLong(p[3]); } catch (NumberFormatException e) { return null; }
        if (id<=0) return null;
        if ("A".equals(p[2])) { pending.remove(id); return null; }
        if (p.length!=5) return null;
        if ("R".equals(p[2])) {
            reply.accept("TS2|"+session+"|A|"+id);
            if (!received.add(id)) return null;
            if (received.size()>4096) received.remove(received.iterator().next());
        } else if (!"U".equals(p[2])) return null;
        return p[4];
    }
    public boolean accepts(String wire) { return wire!=null && wire.startsWith("TS2|"+session+"|"); }
    public void retry(double now, Consumer<String> sender) {
        Iterator<Pending> it=pending.values().iterator();
        while(it.hasNext()) {
            Pending p=it.next();
            if(now>=p.expires) { it.remove(); continue; }
            if(now>=p.next) { p.next=now+0.25; sender.accept(p.wire); }
        }
    }
    public int pendingCount() { return pending.size(); }
}
