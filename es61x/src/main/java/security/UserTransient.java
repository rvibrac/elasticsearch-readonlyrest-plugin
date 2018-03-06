package security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Base64;

public class UserTransient implements Serializable {
    /**
	 * 
	 */
	private static final long serialVersionUID = -8866625802695512997L;
    private final String _filter;
    
    public static UserTransient CreateFromFilter(String filter) {
    	return new UserTransient(filter);
    }
    
//    public static UserTransient CreateFromRequestContext(RequestContext rc, ) {
//        if (rc == null)
//            throw new IllegalArgumentException("You need to provide the request context.");
//
//        if (!rc.getRuleRole().isPresent()) {
//            throw new IllegalStateException("Unable to extract rule and role from request context.");
//        }
//        RuleRole rr = rc.getRuleRole().get();
//        settings = JwtAuthRuleSettings.from(s, JwtAuthDefinitionSettingsCollection.from(s));
//        signingKeyForAlgo = getSigningKeyForAlgo();
//        Optional<String> token = Optional.of(rc.getHeaders()).map(m -> m.get(settings.getHeaderName()))
//        		        .flatMap(UserTransient::extractToken);
////        OAuthToken token = rc.getToken();
//
//        if (token.isPresent()) {
//            if (!rc.getLoggedInUser().isPresent())
//                throw new IllegalStateException("Unable to extract user from request context.");
//
//            return new UserTransient(rc.getLoggedInUser().get().getId(), rr.getRuleId(), rr.getRoleLinked());
//        } else {
//            // Check the role linked is contained in token
//        	Jws<Claims> jws = AccessController.doPrivileged((PrivilegedAction<Jws<Claims>>) () -> {
//                JwtParser parser = Jwts.parser();
//                if (signingKeyForAlgo.isPresent()) {
//                  parser.setSigningKey(signingKeyForAlgo.get());
//                } else {
//                  parser.setSigningKey(settings.getKey());
//                }
//                return parser.parseClaimsJws(token.get());
//              });
//        	Optional<Set<String>> roles = extractRoles(jws);
//            if (!settings.getRolesClaim().isPresent() && (!roles.isPresent() || roles.get().contains(rr.getRoleLinked()))) {
//                throw new IllegalStateException("Unable to merge role from request context & token.");
//            }
//
//            Optional<String> user = settings.getUserClaim().map(claim -> jws.getBody().get(claim, String.class));
//            return new UserTransient(user.get(), rr.getRuleId(), rr.getRoleLinked());
//        }
//    }
//
    private UserTransient(String filter) {
        this._filter = filter;
    }

    public String getFilter() {
    	return this._filter;
    }
    
    @Override
    public String toString() {
        return "{ "
                + "FILTER: " + this._filter
                + "}";
    }
    
    public String serialize() {
    	ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos;
		try {
			oos = new ObjectOutputStream(baos);
			oos.writeObject(this);
	        oos.close();
		} catch (IOException e) {
			return null;
		}
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }
    
    public static UserTransient Deserialize(String userTransientEncoded) {
    	UserTransient userTransient = null;
    	byte [] data = Base64.getDecoder().decode(userTransientEncoded);
        ObjectInputStream ois;
		try {
			ois = new ObjectInputStream(new ByteArrayInputStream(data));
			Object o  = ois.readObject();
			if (o instanceof UserTransient) {
				userTransient = (UserTransient) o;
			}
	        ois.close();
		} catch (IOException e) {
			throw new IllegalStateException("Couldn't extract userTransient from threadContext.");
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException("Couldn't extract userTransient from threadContext.");
		}
		return userTransient;

    }
}