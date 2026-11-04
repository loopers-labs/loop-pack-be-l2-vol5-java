import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.EventRequest;

// Attach only to the lab JVM and hold its target thread at the first instruction.
public class HoldMethodEntry {
    public static void main(String[] args) throws Exception {
        var socket = Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(connector -> connector.name().equals("com.sun.jdi.SocketAttach"))
            .findFirst().orElseThrow();
        var address = socket.defaultArguments();
        address.get("hostname").setValue("127.0.0.1");
        address.get("port").setValue(args[0]);
        address.get("timeout").setValue("5000");
        VirtualMachine vm = socket.attach(address);
        var target = vm.classesByName(args[1]).stream().findFirst().orElseThrow();
        var method = target.methodsByName(args[2]).getFirst();
        var breakpoint = vm.eventRequestManager().createBreakpointRequest(method.location());
        breakpoint.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        breakpoint.addCountFilter(1);
        breakpoint.enable();
        System.out.println("BREAKPOINT_READY");
        for (;;) {
            var events = vm.eventQueue().remove(120_000);
            if (events == null) {
                throw new IllegalStateException("Lab breakpoint timed out.");
            }
            for (var event : events) {
                if (event instanceof BreakpointEvent) {
                    System.out.println("BREAKPOINT_HIT " + args[1] + "." + args[2]);
                    // The lab stops the target JVM before the debugger releases this thread.
                    Thread.sleep(120_000);
                    return;
                }
                if (event instanceof VMDisconnectEvent) {
                    throw new IllegalStateException("Lab JVM disconnected before the breakpoint.");
                }
            }
            events.resume();
        }
    }
}
