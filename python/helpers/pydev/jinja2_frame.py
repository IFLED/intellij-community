
from django_frame import FCode
from pydevd_file_utils import GetFileNameAndBaseFromFile
from runfiles import DictContains

class Jinja2TemplateFrame:

    def __init__(self, frame):
        file_name = get_jinja2_template_filename(frame)
        self.back_context = None
        if 'context' in frame.f_locals:
            self.back_context = frame.f_locals['context']
        self.f_code = FCode('Jinja2 Template', file_name)
        self.f_lineno = get_jinja2_template_line(frame)
        self.f_back = frame
        self.f_globals = {}
        self.f_locals = self.collect_context(frame)
        self.f_trace = None

    def collect_context(self, frame):
        res = {}
        if self.back_context is not None:
            for k, v in self.back_context.iteritems():
                res[k] = v
        for k, v in frame.f_locals.iteritems():
            if not k.startswith('l_'):
                if not k in res:
                    res[k] = v
            else:
                key = k[2:]
                res[key] = v
        return res


def get_jinja2_template_line(frame):
    debug_info = frame.f_globals['__jinja_template__'].debug_info

    if debug_info is None:
        return None

    lineno = frame.f_lineno

    for pair in debug_info:
        if pair[1] == lineno:
            return pair[0]
    return None

def get_jinja2_template_filename(frame):
    if DictContains(frame.f_globals, '__jinja_template__'):
        fname = frame.f_globals['__jinja_template__'].filename
        filename, base = GetFileNameAndBaseFromFile(fname)
        return filename
    return None


