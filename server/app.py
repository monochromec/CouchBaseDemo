import os

from flask import Flask, render_template, request, url_for

from couchbase.cluster import Cluster
from couchbase.cluster import PasswordAuthenticator
from couchbase.bucket import Bucket

from io import BytesIO
from gzip import GzipFile
from base64 import b64encode

# Fixed app nanme for Flask
APPNAME = 'ProductCatalog'
# Default value for Flask static content folder
STATIC_FOLDER = 'static'

# Description: Get all images ending in PNG in static content folder
# If other image formats are required, modify the loop
# Parameters:
#   DIRECTORY: location of content folder
# Return value: list of image file names

def getImages(directory=STATIC_FOLDER):
    exts = ['png']
    ret = []
    for fn in os.listdir(directory):
        fName = os.path.basename(fn)
        ext = fName.split('.', -1)
        if os.path.isfile(os.path.join(directory, fn)) and ext [1] in exts:
            ret.append(fn)
    return ret

# Description: Store name and image bitmap in CB bucket
# Parameters:
#   BUF0: Image bitmap
#   NAME: Name of file w/o extension (this is the caption in the Android app)

def storeImageInCB(buf0, name):
# Default credentials
    auth = PasswordAuthenticator('couchbase', 'couchbase')
    cluster = Cluster('couchbase://localhost')
    cluster.authenticate(auth)
    bucket = cluster.open_bucket('default')
# Encode bitmap with Base64 first, then deflate it using Gzip and then encode it again for string storage in dictionary
# Whether deflating the bitmap directly instead of encoding it first would depend on the compression of the image
# format and algorithm; fine-tuning of this approach is left as an exercise for the reader :-)
    b64b = b64encode(buf0)
    b64s = b64b.decode('utf-8')
    out = BytesIO()
    with GzipFile(fileobj=out, mode='w') as fo:
        fo.write(b64s.encode())
    b64zs = b64encode(out.getvalue()).decode('utf-8')
# DO NOT LEAVE OUT the channels attribute in this dictionary. If you omit this, the Sync Gateway *WILL NOT*
# sync the contents of the document
    json = { 'channels': 'default', 'name': name, 'img': b64zs }
    bucket.upsert('fashionPic', json)

# Standard Flask init stuff
app = Flask(__name__)
app.config[APPNAME] = APPNAME

imgs = getImages()

# The landing page
@app.route('/')
def index():
    return render_template('imageview.html', imgs=imgs)

# Display confirmation page after storing the image alongside the name in the bucket
@app.route('/confirmed')
def confirmed():
    model = request.args.get('filename')
    with open(STATIC_FOLDER + '/' + model + '.png', 'rb') as fil:
        storeImageInCB(fil.read(), model)
    return render_template('confirmed.html', model=model)

if __name__ == '__main__':
    app.run('CB demo')
