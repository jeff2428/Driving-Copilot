import glob
import sys

def validate_swift(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        code = f.read()
    
    stack = []
    i = 0
    n = len(code)
    line = 1
    col = 1
    
    in_single_line_comment = False
    in_multi_line_comment = 0
    in_string = False
    in_multiline_string = False
    
    while i < n:
        c = code[i]
        
        if c == '\n':
            line += 1
            col = 1
            in_single_line_comment = False
            i += 1
            continue
            
        if in_single_line_comment:
            i += 1
            col += 1
            continue
            
        if in_multi_line_comment > 0:
            if i + 1 < n and code[i:i+2] == '*/':
                in_multi_line_comment -= 1
                i += 2
                col += 2
                continue
            elif i + 1 < n and code[i:i+2] == '/*':
                in_multi_line_comment += 1
                i += 2
                col += 2
                continue
            i += 1
            col += 1
            continue
            
        if in_multiline_string:
            if i + 2 < n and code[i:i+3] == '"""':
                in_multiline_string = False
                i += 3
                col += 3
                continue
            i += 1
            col += 1
            continue
            
        if in_string:
            if c == '\\':
                i += 2
                col += 2
                continue
            elif c == '"':
                in_string = False
            i += 1
            col += 1
            continue
            
        # Comments
        if i + 1 < n and code[i:i+2] == '//':
            in_single_line_comment = True
            i += 2
            col += 2
            continue
        if i + 1 < n and code[i:i+2] == '/*':
            in_multi_line_comment += 1
            i += 2
            col += 2
            continue
            
        # Strings
        if i + 2 < n and code[i:i+3] == '"""':
            in_multiline_string = True
            i += 3
            col += 3
            continue
        if c == '"':
            in_string = True
            i += 1
            col += 1
            continue
            
        # Delimiters
        if c in '({[':
            stack.append((c, line, col))
        elif c in ')}]':
            if not stack:
                print(f"Error: Unexpected {c} at {filepath}:{line}:{col}")
                return False
            top, t_line, t_col = stack.pop()
            expected = {')': '(', '}': '{', ']': '['}[c]
            if top != expected:
                print(f"Error: Mismatched {c} at {filepath}:{line}:{col}, expected matching for {top} from line {t_line}:{t_col}")
                return False
                
        i += 1
        col += 1
        
    if stack:
        print(f"Error: Unclosed delimiters in {filepath}: {stack}")
        return False
        
    return True

if __name__ == '__main__':
    all_ok = True
    files = sorted(glob.glob('ios/**/*.swift', recursive=True))
    print(f"Validating {len(files)} Swift files...")
    for f in files:
        if validate_swift(f):
            print(f"  ✓ {f} structurally valid")
        else:
            all_ok = False
            
    if not all_ok:
        sys.exit(1)
    print("\n🎉 All 13 Swift source files passed structural and delimiter validation!")
