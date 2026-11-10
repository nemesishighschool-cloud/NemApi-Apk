/**
 * Tests du hook réseau (Node, sans WebView).
 *
 *   node tools/test-hook.js
 *
 * 1. parseur : trames réelles DeepSeek, format OpenAI, fragments THINK
 * 2. câblage : un XHR qui passe par le prototype doit être vu par le hook et
 *    produire un enregistrement de flux (c'est le test qui aurait attrapé le
 *    « isChatCall is not defined »)
 */
const fs = require('fs');
const path = require('path');

const SRC = fs.readFileSync(path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'js', 'hook.js'), 'utf8');

function makeWorld() {
  // --- faux DOM / DOM APIs ---
  const doc = {
    head: {},
    addEventListener() {},
    querySelector() {
      return null;
    },
    createElement() {
      return { setAttribute() {}, getAttribute() { return null; } };
    },
  };

  function FakeXHR() {
    this.listeners = {};
    this.responseText = '';
    this.responseType = '';
    this.status = 200;
  }
  FakeXHR.prototype.open = function () {};
  FakeXHR.prototype.send = function () {};
  FakeXHR.prototype.addEventListener = function (name, fn) {
    (this.listeners[name] = this.listeners[name] || []).push(fn);
  };
  FakeXHR.prototype.emit = function (name) {
    (this.listeners[name] || []).forEach((f) => f.call(this, {}));
  };

  const win = { addEventListener() {} };
  global.window = win;
  global.document = doc;
  global.location = { origin: 'https://chat.deepseek.com' };
  global.XMLHttpRequest = FakeXHR;
  global.TextDecoder = class {
    decode(v) {
      return String(v);
    }
  };

  new Function('window', 'document', 'location', 'XMLHttpRequest', 'TextDecoder', SRC)(
    win,
    doc,
    global.location,
    FakeXHR,
    global.TextDecoder
  );
  return { win, FakeXHR };
}

let failures = 0;
function check(name, got, want) {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  if (!ok) failures++;
  console.log(`${ok ? 'OK  ' : 'FAIL'} ${name}${ok ? '' : `\n     attendu: ${JSON.stringify(want)}\n     obtenu : ${JSON.stringify(got)}`}`);
}

// ---------- 1. parseur ----------
{
  const { win } = makeWorld();
  const nap = win.__nap;

  const deepseek = `event: ready
data: {"request_message_id":3,"response_message_id":4}

data: {"v":{"response":{"role":"ASSISTANT","status":"WIP","fragments":[{"id":2,"type":"RESPONSE","content":"TEST"}]}}}

data: {"p":"response/fragments/-1/content","o":"APPEND","v":" FL"}

data: {"v":"UX"}

data: {"p":"response","o":"BATCH","v":[{"p":"accumulated_token_usage","v":85},{"p":"quasi_status","v":"FINISHED"}]}

data: {"p":"response/status","o":"SET","v":"FINISHED"}

`;
  check('parseur DeepSeek', JSON.parse(nap.__test(deepseek)), { text: 'TEST FLUX', thinking: '', done: true });

  const openai = 'data: {"choices":[{"delta":{"content":"Bon"}}]}\n\ndata: {"choices":[{"delta":{"content":"jour"}}]}\n\ndata: [DONE]\n\n';
  check('parseur OpenAI', JSON.parse(nap.__test(openai)).text, 'Bonjour');

  const think = `data: {"v":{"response":{"status":"WIP","fragments":[{"type":"THINK","content":"réfléchir"}]}}}
data: {"p":"response/fragments/-1/content","o":"APPEND","v":" encore"}
data: {"v":{"response":{"status":"WIP","fragments":[{"type":"THINK","content":"réfléchir encore"},{"type":"RESPONSE","content":"Voici"}]}}}
data: {"p":"response/fragments/-1/content","o":"APPEND","v":" la réponse"}
`;
  check('sépare THINK de la réponse', JSON.parse(nap.__test(think)).text, 'Voici la réponse');
}

// ---------- 2. câblage XHR ----------
{
  const { win, FakeXHR } = makeWorld();
  const nap = win.__nap;

  const xhr = new FakeXHR();
  xhr.open('POST', '/api/v0/chat/completion');
  xhr.send('{}');

  check('le hook voit la requête de chat', JSON.parse(nap.reqs_seen()), ['POST /api/v0/chat/completion']);
  check('une capture est ouverte', JSON.parse(nap.state()).started, true);
  check('aucune erreur interne', nap.errors, []);

  // le serveur envoie deux morceaux de flux
  xhr.responseText = 'data: {"v":"Salut"}\n\n';
  xhr.emit('progress');
  xhr.responseText += 'data: {"v":" toi"}\n\n';
  xhr.emit('progress');
  check('texte intermédiaire', JSON.parse(nap.state()).text, 'Salut toi');

  xhr.emit('loadend');
  check('flux terminé', JSON.parse(nap.state()).done, true);

  // une route qui ne doit PAS être capturée
  const other = new FakeXHR();
  other.open('POST', '/api/v0/telemetry');
  other.send('{}');
  check('télémétrie ignorée', JSON.parse(nap.state()).url, '/api/v0/chat/completion');
  check('journal des requêtes', JSON.parse(nap.reqs_seen()), ['POST /api/v0/chat/completion', 'POST /api/v0/telemetry']);
}

console.log(failures === 0 ? '\nTous les tests passent.' : `\n${failures} test(s) en échec.`);
process.exit(failures === 0 ? 0 : 1);
