package com.bridj;

import java.lang.reflect.*;
import java.lang.annotation.*;
import java.util.Arrays;

import com.bridj.Demangler.MemberRef.Type;

abstract class Demangler {
	public static class DemanglingException extends Exception {
		public DemanglingException(String mess) {
			super(mess);
		}
	}
	public abstract MemberRef parseSymbol() throws DemanglingException;

	protected final String str;
	protected final int length;
	protected int position = 0;
	public Demangler(String str) {
		this.str = str;
		this.length = str.length();
	}
	
	public static StringBuilder implode(StringBuilder b, Object[] items, String sep) {
		return implode(b, Arrays.asList(items), sep);
	}
	public static StringBuilder implode(StringBuilder b, Iterable<?> items, String sep) {
		boolean first = true;
		for (Object item : items) {
			if (first)
				first = false;
			else
				b.append(sep);
			b.append(item);
		}
		return b;
	}
	protected char peekChar() {
		return position >= length ? 0 : str.charAt(position);
	}
	protected char consumeChar() {
		char c = peekChar();
		if (c != 0)
			position++;
		return c;
	}
	protected boolean consumeCharIf(char... allowedChars) {
		char c = peekChar();
		for (char allowedChar : allowedChars)
			if (allowedChar == c) {
				position++;
				return true;
			}
		return false;
	}
	protected DemanglingException error() {
		return error(null);
	}
	protected DemanglingException error(String mess) {
		return new DemanglingException("Parsing error at position " + position + (mess == null ? "" : ": " + mess));
	}
	
	public interface TemplateArg {
		
	}


	public static class Symbol {
		final String symbol;
		//long address;
		MemberRef ref;
		boolean refParsed;
		final NativeLibrary library;
		
		public Symbol(String symbol, NativeLibrary library) {
			this.symbol = symbol;
			this.library = library;
			
		}
		public boolean matches(Method method) {
			if (!symbol.contains(method.getName()))
				return false;
		
			parse();
			
			if (ref == null)
				return false;
			
			return ref.matches(method);
		}
		void parse() { 
			if (!refParsed) {
				try {
					ref = library.parseSymbol(symbol);
				} catch (DemanglingException ex) {
					ex.printStackTrace();
				}
				refParsed = true;
			}
		}
	}

	public static abstract class TypeRef implements TemplateArg {
		public abstract StringBuilder getQualifiedName(StringBuilder b, boolean generic);
		public boolean matches(Class<?> type) {
			return getQualifiedName(new StringBuilder(), false).toString().equals(type.getName());
		}
		
	}

	public static class Constant implements TemplateArg {
		Object value;
	}

	public static class NamespaceRef extends TypeRef {
		String[] namespace;
		public StringBuilder getQualifiedName(StringBuilder b, boolean generic) {
			return implode(b, namespace, ".");
		}
	}

	protected static TypeRef classType(Class<?> c, Class<? extends Annotation>... annotations) {
		JavaTypeRef tr = new JavaTypeRef();
		tr.type = c;
		tr.annotations = annotations;
		return tr;
	}
	public static class JavaTypeRef extends TypeRef {

		java.lang.reflect.Type type;
		Class<? extends Annotation>[] annotations;
		
		Class<?> getTypeClass() {
			if (type instanceof Class)
				return (Class)type;
			if (type instanceof ParameterizedType)
				return (Class)((ParameterizedType)type).getRawType();
			throw new UnsupportedOperationException("Unknown type type : " + type.getClass().getName());
		}
		@Override
		public StringBuilder getQualifiedName(StringBuilder b, boolean generic) {
			return b.append(getTypeClass().getName());
		}
		@Override
		public boolean matches(Class<?> type) {
			return type.equals(getTypeClass()); // TODO isAssignableFrom or the opposite, depending on context
		}
		
	}
	public static class ClassRef extends TypeRef {
		TypeRef enclosingType;
		String simpleName;
		TemplateArg[] templateArguments;
		
		public StringBuilder getQualifiedName(StringBuilder b, boolean generic) {
			if (enclosingType instanceof ClassRef) {
				enclosingType.getQualifiedName(b, generic).append('$');
			} else if (enclosingType instanceof NamespaceRef) {
				enclosingType.getQualifiedName(b, generic).append('.');
			}
			b.append(simpleName);
			if (generic && templateArguments != null) {
				int args = 0;
				for (int i = 0, n = templateArguments.length; i < n; i++) {
					TemplateArg arg = templateArguments[i];
					if (!(arg instanceof TypeRef))
						continue;
					
					if (args == 0)
						b.append('<');
					else
						b.append(", ");
					((TypeRef)arg).getQualifiedName(b, generic);
					args++;
				}
				if (args > 0)
					b.append('>');
			}
			return b;
		}
	}

	public static class MemberRef {
		public enum Type {
			Constructor, Method, Destructor, CFunction, Field, ScalarDeletingDestructor
		}
		TypeRef enclosingType;
		TypeRef valueType;
		String memberName;
		Boolean isStatic, isProtected, isPrivate;
		public Type type;
		public int modifiers;
		public TypeRef[] paramTypes;
		TemplateArg[] templateArguments;
		
		protected boolean matches(Method method) {
			
			if (enclosingType != null && !enclosingType.matches(method.getDeclaringClass()))
				return false;
			
			if (!memberName.equals(method.getName()))
				return false;
			
			if (!valueType.matches(method.getReturnType()))
				return false;
			
			if (paramTypes != null) {
				Class<?>[] methodArgTypes = method.getParameterTypes();
				int totalArgs = 0; 
				for (int i = 0, n = templateArguments.length; i < n; i++) {
					if (totalArgs >= methodArgTypes.length)
						return false;
					
					Class<?> paramType = methodArgTypes[i];
					
					TemplateArg arg = templateArguments[i];
					if (arg instanceof TypeRef) {
						if (!paramType.equals(Class.class))
							return false;
					} else if (arg instanceof Constant) {
						try {
							paramType.cast(((Constant)arg).value);
						} catch (ClassCastException ex) {
							return false;
						}
					}
					totalArgs++;
				}
				for (int i = 0, n = paramTypes.length; i < n; i++) {
					if (totalArgs >= methodArgTypes.length)
						return false;
					
					if (!paramTypes[i].matches(methodArgTypes[totalArgs]))
						return false;
					
					totalArgs++;
				}
				return totalArgs == methodArgTypes.length;
			}
			
			return true;
		}
	}
}