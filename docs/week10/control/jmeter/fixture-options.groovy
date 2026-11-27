import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

int position = vars.iteration
boolean inject = props.getProperty('incident', 'false').toBoolean() && position in 18..20
if (!inject) {
    vars.put('fixtureOptions', '{"preset":"NORMAL_SUCCESS"}')
    return
}
assert ctx.threadGroup.name == 'fixture' && vars.get('productId') == props.getProperty('teaId')
vars.put('fixtureOptions', '{"preset":"LOST_SUCCESS","overrides":{"callbackMode":"DROP"}}')
def file = Path.of(props.getProperty('runDir')).resolve('incident-injections.csv')
if (position == 18) {
    Files.writeString(file, 'timestampMs,fixturePosition,productId,orderId,attemptKey,preset,callbackMode\n',
        StandardOpenOption.CREATE_NEW)
}
String row = [System.currentTimeMillis(), position, vars.get('productId'), vars.get('orderId'), vars.get('attemptKey'),
              'LOST_SUCCESS', 'DROP'].join(',') + '\n'
Files.writeString(file, row, StandardOpenOption.APPEND)
