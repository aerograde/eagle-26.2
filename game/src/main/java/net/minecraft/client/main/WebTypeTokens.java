package net.minecraft.client.main;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Eagler web-target support. GSON's two TypeToken construction paths both rely on
 * generic reflection that TeaVM does not implement:
 *   - {@code new TypeToken<X>(){}} uses Class.getGenericSuperclass() (returns the
 *     raw class, so it throws "TypeToken isn't parameterized"), and
 *   - {@code TypeToken.getParameterized(raw, args)} uses Class.getTypeParameters()
 *     (returns empty, so it throws "... requires 0 type arguments, but got N").
 *
 * This builds the {@link ParameterizedType} directly and wraps it with the
 * reflection-free {@link TypeToken#get(Type)}, which only reads getRawType() /
 * getActualTypeArguments() off the supplied type — exactly what GSON's adapters
 * need to (de)serialize the value. Behaviour is identical on desktop.
 */
public final class WebTypeTokens {

	private WebTypeTokens() {
	}

	@SuppressWarnings("unchecked")
	public static <T> TypeToken<T> parameterized(final Class<?> rawType, final Type... typeArguments) {
		return (TypeToken<T>) TypeToken.get(new ParameterizedTypeImpl(rawType, typeArguments.clone()));
	}

	private static final class ParameterizedTypeImpl implements ParameterizedType {

		private final Type rawType;
		private final Type[] actualTypeArguments;

		ParameterizedTypeImpl(final Type rawType, final Type[] actualTypeArguments) {
			this.rawType = rawType;
			this.actualTypeArguments = actualTypeArguments;
		}

		@Override
		public Type[] getActualTypeArguments() {
			return this.actualTypeArguments.clone();
		}

		@Override
		public Type getRawType() {
			return this.rawType;
		}

		@Override
		public Type getOwnerType() {
			return null;
		}

		@Override
		public boolean equals(final Object o) {
			if (!(o instanceof ParameterizedType other)) {
				return false;
			}
			return this.rawType.equals(other.getRawType())
				&& java.util.Arrays.equals(this.actualTypeArguments, other.getActualTypeArguments())
				&& other.getOwnerType() == null;
		}

		@Override
		public int hashCode() {
			return this.rawType.hashCode() ^ java.util.Arrays.hashCode(this.actualTypeArguments);
		}
	}
}
