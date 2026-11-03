import json, random, math
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import os
OUT=os.path.join(os.path.dirname(os.path.abspath(__file__)),'demo')+'/'
M=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf',30)
MB=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf',40)
def receipt(name, lines, total, tip, fn, date, time, seed):
    random.seed(seed)
    W,H=760,1180
    r=Image.new('RGB',(W,H),(250,248,242)); d=ImageDraw.Draw(r)
    y=50
    def c(t,f=M): 
        nonlocal y; w=d.textlength(t,font=f); d.text(((W-w)/2,y),t,fill=(30,30,30),font=f); y+=f.size+14
    def lr(a,b,f=M):
        nonlocal y; d.text((50,y),a,fill=(30,30,30),font=f); w=d.textlength(b,font=f); d.text((W-50-w,y),b,fill=(30,30,30),font=f); y+=f.size+14
    c(name,MB); c('Monterrey, N.L.'); c('RFC XAXX010101000'); y+=10
    lr('Fecha '+date,'Hora '+time); d.line((50,y,W-50,y),fill=(60,60,60),width=2); y+=24
    for a,b in lines: lr(a,b)
    y+=6; d.line((50,y,W-50,y),fill=(60,60,60),width=2); y+=24
    sub=sum(float(b.replace(',','')) for _,b in lines)
    lr('SUBTOTAL','%.2f'%sub); lr('IVA incluido','')
    lr('TOTAL','$ %.2f'%total,MB)
    if tip: lr('PROPINA','%.2f'%tip); lr('GRAN TOTAL','$ %.2f'%(total+tip),MB)
    y+=20; lr('VISA ************1234','')
    c('GRACIAS POR SU VISITA')
    # carta su sfondo scuro, leggermente ruotata
    bg=Image.new('RGB',(1200,1600),(40,38,36))
    r=r.rotate(random.uniform(-3,3),expand=True,fillcolor=(40,38,36))
    bg.paste(r,((1200-r.width)//2,(1600-r.height)//2))
    bg=bg.filter(ImageFilter.GaussianBlur(0.6))
    bg.save(OUT+'foto/'+fn,quality=82)
receipt('ASADOR CERRO ALTO',[('2 Arrachera','780.00'),('1 Guacamole','160.00'),('2 Limonada','150.00'),('1 Postre','160.00'),('2 Cafe americano','200.00')],1450.00,145.00,'e1.jpg','27/10/2026','09:42 p.m.',1)
receipt('TAQUERIA EL MEZQUITE',[('3 Tacos pastor','165.00'),('1 Quesadilla','95.00'),('1 Agua fresca','45.00'),('1 Consome','75.00')],380.00,40.00,'e2.jpg','28/10/2026','02:15 p.m.',2)
receipt('HOTEL PLAZA NORTE',[('Hab. sencilla x3','6,000.00'),('Desayuno x3','900.00')],6900.00,0,'e3.jpg','30/10/2026','07:55 a.m.',3)
receipt('TAXI VALLE',[('Aeropuerto - Hotel','320.00')],320.00,0,'e4.jpg','26/10/2026','06:10 p.m.',4)
receipt('CAFE LAUREL',[('1 Capuchino','65.00'),('1 Croissant','30.00')],95.00,0,'e5.jpg','29/10/2026','08:20 a.m.',5)
P='/data/user/0/it.scontrini.trasferta/files/photos/'
T='Monterrey nov 2026'
def ex(id,date,time,place,amount,tip,cat,guests=[],photo=True,**k):
    o=dict(id=id,trip=T,date=date,place=place,amount=amount,tip=tip,currency='MXN',card='VISA 1234',category=cat,guests=guests,pdf=None,
      photo=(P+id+'.jpg') if photo else None,bankId=None,created=1792000000000+int(id[1:])*1000,time=time,tipCash=0.0,lat=25.6714,lon=-100.3093,
      country='MX',poi=place,catReason='',eurRate=0.0476,eurDate=date,declaration=False,updated=1792000000000,zvoce='',pay='az',nights=1,split=False,provider='',route='',note='')
    o.update(k); return o
E=[ex('e1','2026-10-27','21:42','Asador Cerro Alto',1450.0,145.0,'Cena',['Luca Bianchi']),
   ex('e2','2026-10-28','14:15','Taquería El Mezquite',380.0,40.0,'Pranzo'),
   ex('e3','2026-10-30','07:55','Hotel Plaza Norte',6900.0,0.0,'Albergo',nights=3),
   ex('e4','2026-10-26','18:10','Taxi Valle',320.0,0.0,'Taxi',provider='Taxi Valle S.A. de C.V.',route='Aeropuerto MTY - Hotel Plaza Norte'),
   ex('e5','2026-10-29','08:20','Café Laurel',95.0,0.0,'Bar'),
   ex('e6','2026-10-29','21:05','La Terraza',980.0,0.0,'Cena',['Luca Bianchi','Sara Verdi'],photo=False)]
S=[dict(id='s1',date='2026-10-27',amount=1595.0,currency='MXN',merchant='ASADOR CERRO ALTO',card='1234',network='VISA',created=1792000100000,time='21:44',source='sms',done=False,inStmt=False),
   dict(id='s2',date='2026-10-28',amount=86.5,currency='MXN',merchant='OXXO MTY CENTRO',card='1234',network='VISA',created=1792000200000,time='19:02',source='sms',done=False,inStmt=False),
   dict(id='s3',date='2026-10-29',amount=145.0,currency='MXN',merchant='UBER *TRIP',card='1234',network='VISA',created=1792000300000,time='22:31',source='sms',done=False,inStmt=False)]
D=dict(expenses=E,sms=S,trip=T,currency='MXN',senders='',myName='Marco Rossi',myEmail='',myCard='1234',capZone='',repo='',repoToken='',lastSync=0,syncMsg='',
  syncSha={},syncHash={},tombstones={},lockEnabled=False,useLocation=True,capAmount=0.0,capCurrency='',capYear=0,downloadUrl='',tableSeen={})
json.dump(D,open(OUT+'data.json','w'),ensure_ascii=False,indent=1)
