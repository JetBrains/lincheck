#include "lincheck.h"
template<>
struct Lincheck::hash<std::vector<int>> {
    std::size_t operator()(const std::vector<int> &v) const noexcept {
        std::string s;
        for(auto elem : v) {
            s += std::to_string(elem) + ",";
        }
        return std::hash<std::string>()(s);
    }
};

template<>
struct Lincheck::to_string<std::pair<bool, int>> {
    std::string operator()(const std::pair<bool, int> &ret) const noexcept {
        return ret.first ? "{true, " + std::to_string(ret.second) + "}" : "{false, " + std::to_string(ret.second) + "}";
    }
};

template<>
struct Lincheck::hash<std::pair<bool, int>> {
    std::size_t operator()(std::pair<bool, int> &p) const noexcept {
        return p.first ? Lincheck::hash<int>()(p.second) : -1 * Lincheck::hash<int>()(p.second);
    }
};