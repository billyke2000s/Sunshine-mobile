"""Generation-7 pairing transcript matching unmodified Moonlight Android PairingManager."""
import sys, os, ssl, urllib.request, urllib.parse, xml.etree.ElementTree as ET
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

d=Path(sys.argv[1]); cert=x509.load_pem_x509_certificate((d/'client.crt').read_bytes()); private=serialization.load_pem_private_key((d/'client.key').read_bytes(),None)
base={'uniqueid':'test-client','uuid':'test-uuid'}
def request(path,params=None,tls=False,authorized=True):
    ctx=None
    if tls:
        ctx=ssl.create_default_context(cafile=str(d/'host.crt')); ctx.check_hostname=False
        if authorized: ctx.load_cert_chain(str(d/('host.crt' if authorized=='stranger' else 'client.crt')),str(d/('host.key' if authorized=='stranger' else 'client.key')))
    url=('https://127.0.0.1:47984' if tls else 'http://127.0.0.1:47989')+'/'+path+'?'+urllib.parse.urlencode(base|(params or {}))
    with urllib.request.urlopen(url,context=ctx,timeout=10) as resp: return ET.fromstring(resp.read())
def tag(root,name):
    assert root.attrib['status_code']=='200', ET.tostring(root)
    return root.findtext(name)
def sha(data):
    h=hashes.Hash(hashes.SHA256()); h.update(data); return h.finalize()
def aes(key,data,encrypt=True):
    c=Cipher(algorithms.AES(key),modes.ECB()); op=c.encryptor() if encrypt else c.decryptor(); return op.update(data)+op.finalize()

def pair(pin,valid=True,tamper=False):
    salt=os.urandom(16); key=sha(salt+pin.encode())[:16]
    result=request('pair',{'phrase':'getservercert','salt':salt.hex(),'clientcert':(d/'client.crt').read_bytes().hex()})
    assert tag(result,'paired')=='1'
    host=x509.load_pem_x509_certificate(bytes.fromhex(tag(result,'plaincert')))
    assert host.fingerprint(hashes.SHA256())==x509.load_pem_x509_certificate((d/'host.crt').read_bytes()).fingerprint(hashes.SHA256())
    challenge=os.urandom(16)
    result=request('pair',{'clientchallenge':aes(key,challenge).hex()})
    decoded=aes(key,bytes.fromhex(tag(result,'challengeresponse')),False)
    secret=os.urandom(16)
    result=request('pair',{'serverchallengeresp':aes(key,sha(decoded[32:48]+cert.signature+secret)).hex()})
    signed=bytes.fromhex(tag(result,'pairingsecret'))
    host.public_key().verify(signed[16:],signed[:16],padding.PKCS1v15(),hashes.SHA256())
    matches=decoded[:32]==sha(challenge+host.signature+signed[:16])
    assert matches==valid
    signature=private.sign(secret,padding.PKCS1v15(),hashes.SHA256())
    if tamper: signature=bytes([signature[0]^1])+signature[1:]
    result=request('pair',{'clientpairingsecret':(secret+signature).hex()})
    assert tag(result,'paired')==('1' if valid and not tamper else '0')
    return result

if sys.argv[2]=='pair':
    assert request('launch').attrib['status_code']=='401'
    try: request('applist',tls=True,authorized=False); raise AssertionError('Missing cert accepted')
    except (ssl.SSLError,urllib.error.URLError): pass
    assert tag(request('serverinfo'),'PairStatus')=='0'
    pair('0000',False)
    import time; time.sleep(3.1)
    # Out-of-order phases must not authorize a client.
    assert request('pair',{'clientpairingsecret':'00'*272}).attrib['status_code']=='400'
    pair('1234',tamper=True)
    time.sleep(3.1)
    pair('1234')
    assert tag(request('pair',{'phrase':'pairchallenge'},tls=True),'paired')=='1'
    assert tag(request('serverinfo',tls=True),'PairStatus')=='1'
    try: request('applist',tls=True,authorized='stranger'); raise AssertionError('Unpaired cert accepted')
    except (ssl.SSLError,urllib.error.URLError): pass
    assert tag(request('applist',tls=True),'App/ID')=='1'
    print('PASS: wrong PIN, phase ordering, valid pairing, certificate authentication, app listing')
elif sys.argv[2]=='cancel':
    assert tag(request('cancel',tls=True),'cancel')=='1'
    assert tag(request('serverinfo',tls=True),'currentgame')=='0'
    print('PASS: authenticated cancel and session cleanup')
else:
    assert tag(request('serverinfo',tls=True),'PairStatus')=='1'
    verb='resume' if len(sys.argv)>3 and sys.argv[3]=='2' else 'launch'
    result=request(verb,{'appid':'1','corever':'1','mode':'1280x720x30','rikey':'00112233445566778899aabbccddeeff','rikeyid':'12345','surroundAudioInfo':'196610'},tls=True)
    assert tag(result,'resume' if verb=='resume' else 'gamesession')=='1'
    assert tag(result,'sessionUrl0')=='rtspenc://127.0.0.1:48010'
    print('PASS: persisted client identity and authenticated launch')
