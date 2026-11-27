import java.nio.file.Files
import java.time.Duration

SampleResult.setIgnore()
def stopping = { Files.exists(props.get('control.stop')) || Thread.currentThread().interrupted
                 || System.currentTimeMillis() >= ctx.thread.endTime }
String group = ctx.threadGroup.name
String product
if (group == 'water') {
    def nextSlot = props.get('control.nextWaterNs')
    long now = System.nanoTime()
    long start = nextSlot.getAndUpdate { previous -> Math.max(previous, now) + Duration.ofSeconds(1).toNanos() }
    while (System.nanoTime() < start && !stopping()) {
        long remainingMs = (start - System.nanoTime()).intdiv(1_000_000L)
        Thread.sleep(Math.max(1L, Math.min(250L, remainingMs)))
    }
    product = props.getProperty('waterId')
} else {
    int n = vars.iteration
    if (n == 11) {
        while (!Files.exists(props.get('control.secondFixture')) && !stopping()
                && System.currentTimeMillis() < ctx.thread.endTime) {
            Thread.sleep(200)
        }
    }
    if (n > 10 && !Files.exists(props.get('control.secondFixture'))) {
        ctx.thread.stop()
        return
    }
    boolean coffee = n <= 6 || (n >= 11 && n <= 13)
    product = props.getProperty(coffee ? 'coffeeId' : 'teaId')
}
if (stopping()) {
    ctx.thread.stop()
    return
}
vars.put('userId', props.getProperty(group == 'water' ? 'waterUser' : 'fixtureUser'))
vars.put('kind', group)
vars.put('productId', product)
vars.put('cartItems', product + ':1')
vars.put('orderId', '')
vars.put('attemptKey', UUID.randomUUID().toString())
