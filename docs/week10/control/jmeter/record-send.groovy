import java.nio.file.Files
import java.nio.file.StandardOpenOption

String id = vars.get('orderId')
String stage = id ? 'confirm' : 'create'
def ledger = props.get('control.requests')
String row = [System.currentTimeMillis(), vars.get('kind'), stage, vars.get('productId'), id, vars.get('attemptKey'),
              vars.get('userId'), vars.get('cartItems')].join(',') + '\n'
synchronized (ledger) {
    Files.writeString(ledger, row, StandardOpenOption.APPEND)
}
if (stage == 'confirm' && vars.get('kind') == 'water') {
    props.get('control.waterSent').increment()
}
if (stage == 'confirm' && vars.get('kind') == 'mixed') {
    props.get('control.browseCounters').mixedSent.increment()
}
